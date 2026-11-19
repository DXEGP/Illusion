#include "mcdr_abi.h"
#include "mcdr_oit_execution.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr uint32_t extent = 64;
constexpr uint64_t programGeneration = 1;
constexpr uint64_t sourcePassId = 0x7e030001;
const std::string vertexShader = R"(
float4 main(uint vertexId : SV_VertexID) : SV_Position {
    float2 positions[3] = { float2(-1, -1), float2(-1, 3), float2(3, -1) };
    return float4(positions[vertexId], 0.5, 1);
}
)";

void require(bool condition, const std::string& operation) {
    if (condition) return;
    std::array<char, 16384> error{};
    mcdrLastError(error.data(), static_cast<int32_t>(error.size()));
    throw std::runtime_error(operation + ": " + error.data());
}

mcdr_vanilla_frame_input frameInput(uint64_t realFrameId) {
    mcdr_vanilla_frame_input input{};
    input.schemaVersion = MCDR_VANILLA_FRAME_SCHEMA_VERSION;
    input.structSize = sizeof(input);
    input.identity = {1, realFrameId, 1, 1};
    input.historyGeneration = 1;
    input.frameTimeNanos = realFrameId * 16666667;
    input.renderWidth = input.displayWidth = extent;
    input.renderHeight = input.displayHeight = extent;
    input.colorEncoding = input.depthConvention = input.motionConvention = 1;
    input.frameDeltaMs = 16.666667f;
    input.preExposure = input.motionScaleX = input.motionScaleY = input.viewSpaceToMeters = 1;
    input.settings.renderScalePercent = 100;
    input.settings.jitterSamples = 1;
    input.settings.frameGenerationFrames = 1;
    for (size_t index = 0; index < 16; index += 5) {
        input.current.projection[index] = 1;
        input.current.rasterProjection[index] = 1;
        input.current.viewRotation[index] = 1;
    }
    input.current.nearPlane = .1f;
    input.current.farPlane = 100;
    input.current.verticalFovRadians = 1;
    input.current.flags = 257;
    return input;
}

struct Gpu {
    mcdr_command_ctx* context = nullptr;
    mcdr_pipeline* pipeline = nullptr;
    std::array<mcdr_object*, 3> opaque{};
    mcdr_object* target = nullptr;
    mcdr_object* worldDepth = nullptr;
    mcdr_pipeline* boundsCopyPipeline = nullptr;
    mcdr_object* bounds = nullptr;
    mcdr_object* boundsView = nullptr;
    void* frame = nullptr;
    uint64_t frameSerial = 0;
    mcdr_frame_identity identity{};

    Gpu() {
        context = MCDR_D3D12_create_command_encoder();
        require(context != nullptr, "Create command encoder");
    }

    ~Gpu() {
        if (!context) return;
        if (mcdr_dx12GetActiveColorTexture(context)) mcdr_dx12EndRenderPass(context);
        if (frame) {
            const auto aborted = mcdr_dx12OitAbortFrameV1(context, frame);
            if (aborted != 1) std::cerr << "Failure cleanup OIT abort result=" << aborted << '\n';
        }
        if (mcdr_dx12IsListOpen(context) == 1) mcdr_dx12EndCommandList(context);
        const auto destroyed = mcdr_dx12DestroyCommandEncoderCheckedV1(context);
        if (destroyed != MCDR_COMMAND_DESTROY_DESTROYED) {
            std::cerr << "Failure cleanup retains GPU owners; encoder destroy result=" << destroyed << '\n';
            return;
        }
        if (pipeline) mcdr_dx12DestroyPipeline(pipeline);
        if (boundsCopyPipeline) mcdr_dx12DestroyPipeline(boundsCopyPipeline);
        if (boundsView) mcdr_dx12DestroyResource(boundsView);
        if (bounds) mcdr_dx12DestroyResource(bounds);
        if (target) mcdr_dx12DestroyResource(target);
        if (worldDepth) mcdr_dx12DestroyResource(worldDepth);
        for (auto* texture : opaque) if (texture) mcdr_dx12DestroyResource(texture);
    }

    void preparePrograms() {
        void* candidate{};
        require(mcdr_dx12PrepareFramePrograms(context, programGeneration, 0, &candidate) == 1,
            "Prepare frame programs");
        const auto result = mcdr_dx12CommitFramePrograms(context, candidate);
        if (result != 1) mcdr_dx12AbortFramePrograms(candidate);
        require(result == 1, "Commit frame programs");
    }

    void createDrawResources() {
        const std::string pixel = "float4 main() : SV_Target0 { return float4(0.25, 0.5, 0.75, 1); }";
        const mcdr_color_target_desc color{6, 15, 0, 0, 0, 0, 0, 0, 0};
        mcdr_pipeline_desc description{};
        description.vsData = vertexShader.data();
        description.vsLen = static_cast<int32_t>(vertexShader.size());
        description.psData = pixel.data();
        description.psLen = static_cast<int32_t>(pixel.size());
        description.colorCount = 1;
        description.colorTargets = &color;
        description.hasDepth = 1;
        description.depth = {51, 1, 5};
        description.topology = 4;
        pipeline = mcdr_dx12CreateGraphicsPipeline(&description);
        require(pipeline != nullptr, "Create triangle pipeline");
        target = mcdr_dx12CreateTexture(15, 6, extent, extent, 1, 1);
        require(target != nullptr, "Create draw target");
    }

    void createBoundsCopyResources() {
        const std::string pixel = R"(
Texture2D<float4> bounds : register(t0);
float4 main(float4 position : SV_Position) : SV_Target0 {
    return bounds.Load(int3(int2(position.xy), 0));
}
)";
        const mcdr_color_target_desc color{6, 15, 0, 0, 0, 0, 0, 0, 0};
        const mcdr_pipeline_binding_desc binding{1, 0};
        mcdr_pipeline_desc description{};
        description.vsData = vertexShader.data();
        description.vsLen = static_cast<int32_t>(vertexShader.size());
        description.psData = pixel.data();
        description.psLen = static_cast<int32_t>(pixel.size());
        description.colorCount = 1;
        description.colorTargets = &color;
        description.topology = 4;
        description.bindingCount = 1;
        description.bindings = &binding;
        boundsCopyPipeline = mcdr_dx12CreateGraphicsPipeline(&description);
        require(boundsCopyPipeline != nullptr, "Create bounds copy pipeline");
        bounds = mcdr_dx12CreateTexture(15, 6, extent, extent, 1, 2);
        require(bounds != nullptr, "Create bounds source with distinct mip subresources");
        boundsView = mcdr_dx12CreateTextureView(bounds, 0, 1);
        require(boundsView != nullptr, "Create bounds mip-zero SRV");
        mcdr_dx12ClearColorTexture(context, bounds, 0.25f, 0.5f, 0.75f, 1.0f);
    }

    void beginFrame(uint64_t realFrameId) {
        createDrawResources();
        for (auto& texture : opaque) {
            texture = mcdr_dx12CreateTexture(15, 6, extent, extent, 1, 1);
            require(texture != nullptr, "Create opaque input");
        }
        const auto input = frameInput(realFrameId);
        identity = input.identity;
        frameSerial = mcdr_dx12GetFenceValue(context) + 1;
        mcdr_dx12BeginCommandList(context);
        require(mcdr_dx12IsListOpen(context) == 1, "Begin command list");
        require(mcdr_dx12BeginDisplayFrame(context) == 1, "Begin actual display frame");
        if (realFrameId == 1) {
            mcdr_oit_execution_operation_begin_v1 missing{};
            missing.schemaVersion = 1;
            missing.structSize = sizeof(missing);
            missing.identity = identity;
            missing.frameSerial = frameSerial;
            missing.kind = MCDR_OIT_EXECUTION_V2_DRAW;
            missing.programGeneration = programGeneration;
            missing.key.sourcePassId = sourcePassId;
            missing.key.sourceDrawId = 1;
            missing.key.producerKind = MCDR_OIT_EXECUTION_V2_FRAME;
            void* operation = reinterpret_cast<void*>(uintptr_t{1});
            require(mcdr_dx12OitBeginExecutionOperationV1(context, nullptr, &missing, &operation) < 0 &&
                !operation, "Missing frame rejects begin and clears token");
            uint64_t receiptCount = UINT64_MAX;
            require(mcdr_dx12OitFinishExecutionOperationV1(context, nullptr, nullptr, &receiptCount) < 0 &&
                !receiptCount, "Missing frame rejects finish and clears count");
            require(mcdr_dx12OitCancelExecutionOperationV1(context, nullptr, nullptr) < 0,
                "Missing frame rejects cancel");
            mcdr_oit_execution_operation_receipt_v1 receipt{};
            receipt.receiptId = UINT64_MAX;
            require(mcdr_dx12OitQueryExecutionOperationReceiptV1(context, nullptr, nullptr, 0, &receipt) < 0 &&
                !receipt.receiptId, "Missing frame rejects query and clears receipt");
            std::cout << "oit-operation-invalid-frame-contracts=4\n";
        }
        require(mcdr_dx12VanillaBeginFrame(context, &input) == 1, "Freeze vanilla frame");
        worldDepth = mcdr_dx12CreateTexture(15, 51, extent, extent, 1, 1);
        require(worldDepth != nullptr, "Create world depth");
        if (realFrameId == 1) {
            mcdr_dx12ClearDepthTexture(context, worldDepth, 0.0);
            require(mcdr_dx12VanillaRegisterWorldDepthV1(context, worldDepth) != 1,
                "Reject world depth registration after clear");
            std::cout << "world-depth-registration=late-rejected\n";
        } else {
            require(mcdr_dx12VanillaRegisterWorldDepthV1(context, worldDepth) == 1,
                "Register world depth with no motion target");
            const uint32_t seedLimit = realFrameId == 3 ? 1 : 8;
            require(mcdr_dx12VanillaArmWorldDepthSeedV1(context, seedLimit, 64ull * 1024 * 1024) == 1,
                "Arm opaque seed before clear");
            mcdr_dx12ClearDepthTexture(context, worldDepth, 0.0);
            const int32_t mip = 0, clear = 1;
            const std::array<float, 4> clearColor{};
            mcdr_dx12BeginRenderPass(context, &target, 1, &mip, &clear, clearColor.data(),
                worldDepth, 0, 0, 0, 0, 0, extent, extent);
            require(mcdr_dx12SetPipeline(context, pipeline, 1) == 1, "Bind opaque depth writer");
            require(mcdr_dx12Draw(context, 3, 1, 0, 0) == 1, "Record opaque depth draw");
            mcdr_dx12EndRenderPass(context);
            const auto expectedSeal = realFrameId == 3 ? -3 : 1;
            require(mcdr_dx12VanillaSealWorldDepthSeedV1(context) == expectedSeal, "Seal opaque seed at OIT entry");
            require(mcdr_dx12VanillaSealWorldDepthSeedV1(context) == expectedSeal, "Repeat seal of same opaque seed");
            if (realFrameId == 3) std::cout << "world-depth-seed=exhausted-before-oit-draws\n";
            std::cout << "world-depth-registration=motion-independent\n";
        }
        mcdr_oit_begin_v1 begin{};
        begin.schemaVersion = 1;
        begin.structSize = sizeof(begin);
        begin.identity = identity;
        begin.historyGeneration = 1;
        begin.programGeneration = programGeneration;
        begin.totalBudgetBytes = 64ull * 1024 * 1024;
        begin.maxRecords = extent * extent;
        begin.maxRecordsPerPixel = 1;
        begin.width = begin.height = extent;
        begin.colorEncoding = 1;
        begin.preExposure = 1;
        begin.maxSourceRows = begin.maxPrimitiveRows = 1;
        begin.opaqueColor = {opaque[0], 0, 1};
        begin.opaqueDepth = {opaque[1], 0, 1};
        begin.opaqueMotion = {opaque[2], 0, 1};
        require(mcdr_dx12OitBeginFrameV1(context, &begin, &frame) == 1 && frame,
            "Begin actual OIT frame");
    }

    std::vector<mcdr_oit_execution_operation_receipt_v1> recordDraws(uint64_t sourceDrawId, uint32_t count,
        uint32_t kind = MCDR_OIT_EXECUTION_V2_DRAW) {
        auto* drawPipeline = kind == MCDR_OIT_EXECUTION_V2_COPY_BOUNDS ? boundsCopyPipeline : pipeline;
        mcdr_oit_execution_operation_begin_v1 begin{};
        begin.schemaVersion = 1;
        begin.structSize = sizeof(begin);
        begin.identity = identity;
        begin.frameSerial = frameSerial;
        begin.key.sourcePassId = sourcePassId;
        begin.key.sourceDrawId = sourceDrawId;
        begin.key.producerKind = MCDR_OIT_EXECUTION_V2_FRAME;
        begin.kind = kind;
        begin.expectedCommandFamily = 1;
        begin.programGeneration = programGeneration;
        void* operation{};
        require(mcdr_dx12OitBeginExecutionOperationV1(context, frame, &begin, &operation) == 1,
            "Begin draw operation");
        std::vector<mcdr_vanilla_resource_use> uses{{target, identity.resourceVersion, 0, 1, 2, 4, 0, 0}};
        if (kind == MCDR_OIT_EXECUTION_V2_COPY_BOUNDS)
            uses.push_back({bounds, identity.resourceVersion, 0, 1, 1, 192, 192, 0});
        void* pass{};
        require(mcdr_dx12VanillaBeginDeclaredPass(context, static_cast<uint32_t>(sourcePassId),
            uses.data(), static_cast<uint32_t>(uses.size()), 3, &pass) == 1, "Begin declared draw pass");
        const int32_t mip = 0, clear = 1;
        const std::array<float, 4> clearColor{};
        mcdr_dx12BeginRenderPass(context, &target, 1, &mip, &clear, clearColor.data(),
            nullptr, 0, 0, 1, 0, 0, extent, extent);
        require(mcdr_dx12SetPipeline(context, drawPipeline, 0) == 1, "Bind actual draw pipeline");
        if (kind == MCDR_OIT_EXECUTION_V2_COPY_BOUNDS) {
            const mcdr_binding_slot binding{1, 0, nullptr, boundsView, 0, 0};
            require(mcdr_dx12PushDescriptors(context, &binding, 1) == 1, "Bind actual bounds source SRV");
        }
        for (uint32_t index = 0; index < count; ++index)
            require(mcdr_dx12Draw(context, 3, 1, 0, 0) == 1, "Record triangle draw");
        mcdr_dx12EndRenderPass(context);
        require(mcdr_dx12VanillaFinishDeclaredPass(context, pass) == 1, "Finish declared draw pass");
        uint64_t receiptCount = UINT64_MAX;
        require(mcdr_dx12OitFinishExecutionOperationV1(context, frame, operation, &receiptCount) == 1 &&
            receiptCount == count, "Finish operation reports actual draws");
        std::vector<mcdr_oit_execution_operation_receipt_v1> receipts;
        uint64_t after = 0;
        for (uint32_t index = 0; index < count; ++index) {
            mcdr_oit_execution_operation_receipt_v1 receipt{};
            require(mcdr_dx12OitQueryExecutionOperationReceiptV1(context, frame, operation, after, &receipt) == 1,
                "Query actual draw receipt");
            require(receipt.schemaVersion == 1 && receipt.structSize == sizeof(receipt) &&
                receipt.receiptId && receipt.nativeSequence > after && receipt.frameSerial == frameSerial &&
                receipt.identity.realFrameId == identity.realFrameId && receipt.identity.worldEpoch == identity.worldEpoch &&
                receipt.identity.configVersion == identity.configVersion && receipt.resourceVersion == identity.resourceVersion &&
                receipt.identity.resourceVersion == identity.resourceVersion && receipt.programGeneration == programGeneration &&
                receipt.pipelineIdentity == reinterpret_cast<uintptr_t>(drawPipeline) && receipt.packetId && receipt.readSetId &&
                receipt.recordState == MCDR_OIT_OPERATION_RECORDED && receipt.commandFamily == 1 &&
                receipt.kind == kind && receipt.key.sourceDrawId == sourceDrawId && receipt.key.commandOrdinal == index,
                "Receipt preserves native draw identity");
            after = receipt.nativeSequence;
            receipts.push_back(receipt);
            std::cout << "draw-receipt frame=" << identity.realFrameId << " receiptId=" << receipt.receiptId
                << " nativeSequence=" << receipt.nativeSequence << " packetId=" << receipt.packetId
                << " contentState=" << receipt.contentState << " adapterState=" << receipt.adapterState << '\n';
        }
        mcdr_oit_execution_operation_receipt_v1 exhausted{};
        exhausted.receiptId = UINT64_MAX;
        require(mcdr_dx12OitQueryExecutionOperationReceiptV1(context, frame, operation, after, &exhausted) == 0 &&
            exhausted.receiptId == 0, "Receipt enumeration ends with cleared output");
        return receipts;
    }

    void retireFrame() {
        require(mcdr_dx12OitAbortFrameV1(context, frame) == 1, "Retire probe OIT frame");
        frame = nullptr;
        mcdr_dx12DestroyPipeline(pipeline);
        pipeline = nullptr;
        if (boundsCopyPipeline) mcdr_dx12DestroyPipeline(boundsCopyPipeline);
        boundsCopyPipeline = nullptr;
        if (boundsView) mcdr_dx12DestroyResource(boundsView);
        boundsView = nullptr;
        if (bounds) mcdr_dx12DestroyResource(bounds);
        bounds = nullptr;
        mcdr_dx12DestroyResource(target);
        target = nullptr;
        uint64_t fence{};
        require(mcdr_dx12SubmitV1(context, &fence) == MCDR_COMMAND_OUTCOME_SUBMITTED && fence,
            "Submit commands after draw resource destroy requests");
        require(mcdr_dx12WaitForFence(context, fence, 60000000000LL) == 1,
            "Wait for draw resource retirement fence");
        require(mcdr_dx12AbortDisplayFrame(context) == 1, "Retire display frame after fence");
        for (auto& texture : opaque) {
            mcdr_dx12DestroyResource(texture);
            texture = nullptr;
        }
        std::cout << "destroy-request-before-submit fence=" << fence << " completed=1\n";
    }

    void close() {
        require(mcdr_dx12DestroyCommandEncoderCheckedV1(context) == MCDR_COMMAND_DESTROY_DESTROYED,
            "Destroy encoder after completed fences");
        context = nullptr;
    }
};

mcdr_oit_execution_event_v2 event(uint32_t kind, uint64_t sequence) {
    mcdr_oit_execution_event_v2 value{};
    value.schemaVersion = 2;
    value.structSize = sizeof(value);
    value.kind = kind;
    value.sequence = sequence;
    value.key.producerKind = MCDR_OIT_EXECUTION_V2_FRAME;
    value.key.sourcePassId = sourcePassId;
    value.programGeneration = programGeneration;
    return value;
}

mcdr_oit_execution_event_v2 receiptEvent(const mcdr_oit_execution_operation_receipt_v1& receipt, uint64_t sequence) {
    auto value = event(receipt.kind, sequence);
    value.key = receipt.key;
    value.pipelineIdentity = receipt.pipelineIdentity;
    value.shaderGeneration = receipt.shaderGeneration;
    return value;
}

void runFrame(Gpu& gpu, uint64_t realFrameId, const mcdr_oit_execution_operation_receipt_v1* previous,
    mcdr_oit_execution_operation_receipt_v1& saved) {
    gpu.beginFrame(realFrameId);
    const auto receipts = gpu.recordDraws(101, 2);
    saved = receipts[0];
    const auto omitted = gpu.recordDraws(102, 1).front();
    require(receipts[0].receiptId != receipts[1].receiptId && receipts[1].receiptId != omitted.receiptId &&
        receipts[1].nativeSequence < omitted.nativeSequence, "Native sequence and receipt IDs distinguish draws");
    mcdr_oit_execution_begin_v2 begin{};
    begin.schemaVersion = 2;
    begin.structSize = sizeof(begin);
    begin.identity = gpu.identity;
    begin.frameSerial = gpu.frameSerial;
    begin.historyGeneration = 1;
    begin.programGeneration = programGeneration;
    begin.maxEvents = 16;
    begin.maxScopes = begin.maxPasses = begin.maxResources = begin.maxReceipts = 4;
    void* stream{};
    require(mcdr_dx12OitBeginExecutionStreamV2(gpu.context, gpu.frame, &begin, &stream) == 1,
        "Begin execution stream");
    auto boundary = event(MCDR_OIT_EXECUTION_V2_PASS_BEGIN, 1);
    uint64_t eventId{};
    require(mcdr_dx12OitAppendExecutionEventV2(gpu.context, stream, &boundary, &eventId) == 1 && eventId == 1,
        "Append source pass begin");
    uint64_t sequence = 2;
    if (previous) {
        auto input = receiptEvent(*previous, sequence);
        eventId = UINT64_MAX;
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, previous, &eventId) < 0 &&
            eventId == 0, "Reject receipt from previous actual frame");
    }
    for (const auto& original : receipts) {
        auto forged = original;
        ++forged.frameSerial;
        const auto input = receiptEvent(original, sequence);
        eventId = UINT64_MAX;
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &forged, &eventId) < 0 &&
            eventId == 0, "Reject forged receipt frame serial");
        forged = original;
        ++forged.identity.realFrameId;
        eventId = UINT64_MAX;
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &forged, &eventId) < 0 &&
            eventId == 0, "Reject forged receipt frame identity");
    }
    for (auto iterator = receipts.rbegin(); iterator != receipts.rend(); ++iterator) {
        auto input = receiptEvent(*iterator, sequence);
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &*iterator, &eventId) == 1 &&
            eventId == sequence, "Append original operation receipt in reverse native order");
        ++sequence;
        input.sequence = sequence;
        eventId = UINT64_MAX;
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &*iterator, &eventId) < 0 &&
            eventId == 0, "Reject repeated operation receipt");
    }
    if (previous) {
        const auto input = receiptEvent(omitted, sequence);
        require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &omitted, &eventId) == 1 &&
            eventId == sequence, "Append final actual operation receipt");
        ++sequence;
    }
    boundary = event(MCDR_OIT_EXECUTION_V2_PASS_END, sequence);
    require(mcdr_dx12OitAppendExecutionEventV2(gpu.context, stream, &boundary, &eventId) == 1 && eventId == sequence,
        "Append source pass end");
    mcdr_oit_execution_info_v2 info{};
    require(mcdr_dx12OitQueryExecutionStreamV2(gpu.context, stream, &info) == 1 && info.eventCount == sequence &&
        info.receiptCount == 0 && info.consumedReceiptCount == 0 && info.scopeCount == 0 && info.passCount == 1 &&
        info.openPassCount == 0, "Legacy stream counts remain terrain-only");
    const mcdr_oit_execution_pass_v2 pass{2, sizeof(pass), MCDR_OIT_EXECUTION_V2_PASS_RETURNED, 0,
        sourcePassId, static_cast<uint32_t>(sequence), 0, 0, 0};
    mcdr_oit_execution_manifest_v2 manifest{};
    manifest.schemaVersion = 2;
    manifest.structSize = sizeof(manifest);
    manifest.eventCount = static_cast<uint32_t>(sequence);
    manifest.passCount = 1;
    manifest.lastSequence = sequence;
    manifest.passes = &pass;
    void* sealed = reinterpret_cast<void*>(uintptr_t{1});
    mcdr_oit_execution_seal_result_v2 result{};
    require(mcdr_dx12OitSealExecutionStreamV2(gpu.context, stream, &manifest, &sealed, &result) < 0 && !sealed &&
        result.schemaVersion == 2 && result.state == MCDR_OIT_EXECUTION_V2_STATE_UNAVAILABLE &&
        result.expected == 0 && result.observed == 0 && !(result.missingMask & MCDR_OIT_EXECUTION_V2_GAP_MANIFEST),
        "Seal preserves unsupported execution and terrain-only manifest counts");
    require(previous ? !(result.missingMask & MCDR_OIT_EXECUTION_V2_GAP_RECEIPT) && result.receiptId == 0 :
        (result.missingMask & MCDR_OIT_EXECUTION_V2_GAP_RECEIPT) && result.receiptId == omitted.receiptId,
        "Seal reports omitted original operation receipt ID");
    require(((result.missingMask & MCDR_OIT_EXECUTION_V2_GAP_OPAQUE_SEED) != 0) == (realFrameId != 2),
        "Seal reports the registered opaque clear and depth draw");
    std::cout << "operation-stream frame=" << realFrameId << " missingMask=" << result.missingMask
        << " missingReceiptId=" << result.receiptId << " terrainExpected=" << result.expected
        << " terrainObserved=" << result.observed << '\n';
    gpu.retireFrame();
}

enum class BoundsCopyClaim { BoundSource, OtherTexture, OtherSubresource };

void runBoundsCopyFrame(Gpu& gpu, uint64_t realFrameId, BoundsCopyClaim claim) {
    gpu.beginFrame(realFrameId);
    gpu.createBoundsCopyResources();
    const auto receipt = gpu.recordDraws(201, 1, MCDR_OIT_EXECUTION_V2_COPY_BOUNDS).front();
    mcdr_oit_execution_begin_v2 begin{};
    begin.schemaVersion = 2;
    begin.structSize = sizeof(begin);
    begin.identity = gpu.identity;
    begin.frameSerial = gpu.frameSerial;
    begin.historyGeneration = 1;
    begin.programGeneration = programGeneration;
    begin.maxEvents = 3;
    begin.maxScopes = begin.maxPasses = begin.maxReceipts = 1;
    begin.maxResources = 2;
    void* stream{};
    require(mcdr_dx12OitBeginExecutionStreamV2(gpu.context, gpu.frame, &begin, &stream) == 1,
        "Begin bounds copy execution stream");
    auto boundary = event(MCDR_OIT_EXECUTION_V2_PASS_BEGIN, 1);
    uint64_t eventId{};
    require(mcdr_dx12OitAppendExecutionEventV2(gpu.context, stream, &boundary, &eventId) == 1 && eventId == 1,
        "Append bounds copy pass begin");
    std::array<mcdr_oit_execution_resource_v2, 2> resources{};
    for (auto& resource : resources) {
        resource.schemaVersion = 2;
        resource.structSize = sizeof(resource);
        resource.contentRequirement = MCDR_OIT_EXECUTION_V2_CONTENT_OBJECT_ONLY;
        resource.subresourceCount = 1;
        resource.resourceVersion = gpu.identity.resourceVersion;
    }
    resources[0].role = MCDR_OIT_EXECUTION_V2_RESOURCE_BOUNDS;
    resources[0].access = MCDR_OIT_EXECUTION_V2_ACCESS_READ;
    resources[0].object = claim == BoundsCopyClaim::OtherTexture ? gpu.opaque[0] : gpu.bounds;
    resources[0].firstSubresource = claim == BoundsCopyClaim::OtherSubresource ? 1 : 0;
    resources[1].role = MCDR_OIT_EXECUTION_V2_RESOURCE_CULLED_BOUNDS;
    resources[1].access = MCDR_OIT_EXECUTION_V2_ACCESS_WRITE;
    resources[1].object = gpu.target;
    for (auto& resource : resources)
        resource.resourceIdentity = reinterpret_cast<uintptr_t>(resource.object);
    auto input = receiptEvent(receipt, 2);
    input.resourceCount = static_cast<uint32_t>(resources.size());
    input.resources = resources.data();
    require(mcdr_dx12OitAppendExecutionOperationEventV1(gpu.context, stream, &input, &receipt, &eventId) == 1 &&
        eventId == 2, "Append bounds copy receipt with structurally valid texture claims");
    boundary = event(MCDR_OIT_EXECUTION_V2_PASS_END, 3);
    require(mcdr_dx12OitAppendExecutionEventV2(gpu.context, stream, &boundary, &eventId) == 1 && eventId == 3,
        "Append bounds copy pass end");
    const mcdr_oit_execution_pass_v2 pass{2, sizeof(pass), MCDR_OIT_EXECUTION_V2_PASS_RETURNED, 0,
        sourcePassId, 3, 0, 0, 0};
    mcdr_oit_execution_manifest_v2 manifest{};
    manifest.schemaVersion = 2;
    manifest.structSize = sizeof(manifest);
    manifest.eventCount = 3;
    manifest.passCount = 1;
    manifest.resourceCount = static_cast<uint32_t>(resources.size());
    manifest.lastSequence = 3;
    manifest.passes = &pass;
    void* sealed = reinterpret_cast<void*>(uintptr_t{1});
    mcdr_oit_execution_seal_result_v2 result{};
    require(mcdr_dx12OitSealExecutionStreamV2(gpu.context, stream, &manifest, &sealed, &result) < 0 && !sealed &&
        result.schemaVersion == 2 && result.state == MCDR_OIT_EXECUTION_V2_STATE_UNAVAILABLE &&
        !(result.missingMask & (MCDR_OIT_EXECUTION_V2_GAP_MANIFEST | MCDR_OIT_EXECUTION_V2_GAP_RECEIPT)),
        "Bounds copy seal preserves valid manifest and consumed native receipt");
    const bool mismatched = claim != BoundsCopyClaim::BoundSource;
    require(((result.missingMask & MCDR_OIT_EXECUTION_V2_GAP_READ_SET) != 0) == mismatched &&
        result.receiptId == (mismatched ? receipt.receiptId : 0),
        "Bounds copy seal checks actual sampled texture and mip against claimed read set");
    std::cout << "copy-bounds frame=" << realFrameId << " claim=" << static_cast<uint32_t>(claim)
        << " readSetGap=" << mismatched << " missingReceiptId=" << result.receiptId << '\n';
    gpu.retireFrame();
}
}

int main() {
    try {
        std::array<char, 16384> description{};
        require(mcdr_dx12CreateDevice(description.data(), static_cast<int32_t>(description.size())) > 0,
            "Create device");
        std::cout << description.data() << '\n';
        std::array<mcdr_oit_execution_operation_receipt_v1, 3> receipts{};
        for (size_t frameIndex = 0; frameIndex < receipts.size(); ++frameIndex) {
            Gpu gpu;
            gpu.preparePrograms();
            runFrame(gpu, frameIndex + 1, frameIndex == 0 ? nullptr : &receipts[frameIndex - 1], receipts[frameIndex]);
            gpu.close();
        }
        const std::array claims{BoundsCopyClaim::BoundSource, BoundsCopyClaim::OtherTexture, BoundsCopyClaim::OtherSubresource};
        for (size_t claimIndex = 0; claimIndex < claims.size(); ++claimIndex) {
            Gpu gpu;
            gpu.preparePrograms();
            runBoundsCopyFrame(gpu, 4 + claimIndex, claims[claimIndex]);
            gpu.close();
        }
        require(mcdr_dx12DestroyDeviceChecked() == 1, "Destroy completed device");
        std::cout << "oit-operation-real-frames=3 real-draws=9 duplicate-and-forged-receipts=rejected\n";
        std::cout << "copy-bounds-real-frames=3 real-draws=3 texture-and-subresource-mismatches=rejected\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        const auto destroyed = mcdr_dx12DestroyDeviceChecked();
        if (destroyed != 1) std::cerr << "Failure cleanup device destroy result=" << destroyed << '\n';
        return 1;
    }
}
