#include "mcdr_abi.h"

#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr uint32_t dispatchesPerContext = 64;
constexpr uint32_t valueBytes = sizeof(uint32_t);
constexpr int64_t outputBytes = dispatchesPerContext * 2 * valueBytes;
constexpr int64_t inputBytes = 1024;
constexpr uint32_t cbvValue = 7;
constexpr uint32_t srvValue = 11;
constexpr uint32_t sentinel = 0xC0FFEEu;

std::string lastError() {
    std::array<char, 16384> message{};
    mcdrLastError(message.data(), static_cast<int32_t>(message.size()));
    return message.data();
}

void require(bool success, const std::string& operation) {
    if (!success) throw std::runtime_error(operation + ": " + lastError());
}

void beginRecording(mcdr_command_ctx* context) {
    mcdr_dx12BeginCommandList(context);
    require(mcdr_dx12IsListOpen(context) == 1, "Begin recording list state");
}

uint64_t submitRecording(mcdr_command_ctx* context) {
    const uint64_t fence = mcdr_dx12Submit(context);
    require(fence != 0, "Submit recording");
    return fence;
}

void waitForRecording(mcdr_command_ctx* context, uint64_t fence) {
    require(mcdr_dx12WaitForFence(context, fence, 10'000'000'000LL) == 1, "Wait for recording");
}

int32_t dispatchRaw(mcdr_command_ctx* context, void* program, const std::array<mcdr_object*, 4>& buffers,
                    const std::array<uint64_t, 4>& offsets, const std::array<uint64_t, 4>& lengths,
                    const void* records, uint32_t recordCount, uint32_t recordStride) {
    return mcdr_dx12DispatchWorkGraph(context, program, records, recordCount, recordStride,
        buffers.data(), offsets.data(), lengths.data(), static_cast<uint32_t>(buffers.size()));
}

void dispatchValue(mcdr_command_ctx* context, void* program, mcdr_object* shared,
                   mcdr_object* output, uint32_t offset, uint32_t value,
                   bool reverseBindings) {
    alignas(16) const std::array<uint32_t, 2> record{offset, value};
    const std::array<mcdr_object*, 4> buffers{shared, shared, output, output};
    const std::array<uint64_t, 4> offsets = reverseBindings
        ? std::array<uint64_t, 4>{valueBytes * 64, 0, 0, 0}
        : std::array<uint64_t, 4>{0, valueBytes * 64, 0, 0};
    const std::array<uint64_t, 4> lengths{256, valueBytes, outputBytes, outputBytes};
    require(dispatchRaw(context, program, buffers, offsets, lengths, record.data(), 1,
        static_cast<uint32_t>(sizeof(record))) == 1, "Dispatch Work Graph");
}

void expectRejected(int32_t status, const std::string& operation) {
    if (status != 0) throw std::runtime_error(operation + " was accepted");
}

void copyBuffer(mcdr_command_ctx* context, mcdr_object* source, mcdr_object* destination,
                int64_t bytes) {
    require(mcdr_dx12CopyBufferChecked(context, source, 0, destination, 0, bytes) == 1,
        "Initialize buffer");
}

void verifyWords(mcdr_object* readback, int64_t offset, const std::vector<uint32_t>& expected) {
    int64_t mappedBytes = 0;
    const int64_t bytes = static_cast<int64_t>(expected.size() * valueBytes);
    void* mapped = mcdr_dx12MapBuffer(readback, offset, bytes, 1, 0, &mappedBytes);
    require(mapped != nullptr && mappedBytes >= bytes, "Map verification buffer");
    for (size_t index = 0; index < expected.size(); ++index) {
        uint32_t actual = 0;
        std::memcpy(&actual, static_cast<uint8_t*>(mapped) + index * valueBytes, valueBytes);
        if (actual != expected[index]) {
            mcdr_dx12UnmapBuffer(readback);
            throw std::runtime_error("Unexpected Work Graph value at " + std::to_string(index));
        }
    }
    mcdr_dx12UnmapBuffer(readback);
}

void expectSentinel(mcdr_object* readback, int64_t bytes) {
    std::vector<uint32_t> expected(static_cast<size_t>(bytes / valueBytes), sentinel);
    verifyWords(readback, 0, expected);
}

}

int main() {
    try {
        std::array<char, 16384> description{};
        require(mcdr_dx12CreateDevice(description.data(), static_cast<int32_t>(description.size())) > 0,
            "Create device");
        std::cout << description.data() << '\n';
        require(mcdr_dx12GetAdapterInfo(description.data(), static_cast<int32_t>(description.size())) > 0,
            "Query adapter");
        std::cout << description.data() << '\n';

        constexpr char source[] = R"(
            struct InputRecord { uint offset; uint value; };
            cbuffer InputConstants : register(b0) { uint cbvValue; uint cbvPadding[63]; };
            ByteAddressBuffer input : register(t0);
            RWByteAddressBuffer output0 : register(u0);
            RWByteAddressBuffer output1 : register(u1);
            [Shader("node")][NodeLaunch("thread")][NodeIsProgramEntry]
            void Entry(ThreadNodeInputRecord<InputRecord> record) {
                uint value = cbvValue + input.Load(0) + record.Get().value;
                output0.Store(record.Get().offset, value);
                output1.Store(record.Get().offset, value);
            }
        )";
        const int32_t dxilBytes = mcdr_dx12CompileShaderDxc(source, sizeof(source) - 1,
            "", "lib_6_9", nullptr, 0);
        require(dxilBytes > 0, "Compile Work Graph size");
        std::vector<uint8_t> dxil(static_cast<size_t>(dxilBytes));
        require(mcdr_dx12CompileShaderDxc(source, sizeof(source) - 1, "", "lib_6_9",
            dxil.data(), dxilBytes) == dxilBytes, "Compile Work Graph");

        constexpr std::array<uint32_t, 12> normalBindings{0, 0, 0, 1, 0, 0, 2, 0, 0, 2, 1, 0};
        constexpr std::array<uint32_t, 12> reversedBindings{1, 0, 0, 0, 0, 0, 2, 0, 0, 2, 1, 0};
        void* normalProgram = mcdr_dx12CreateWorkGraph(1, dxil.data(), dxilBytes,
            "WorkGraphReuseNormal", "Entry", 8, normalBindings.data(), 4, 512ull * 1024 * 1024);
        require(normalProgram != nullptr, "Create normal Work Graph");
        void* reversedProgram = mcdr_dx12CreateWorkGraph(1, dxil.data(), dxilBytes,
            "WorkGraphReuseReversed", "Entry", 8, reversedBindings.data(), 4, 512ull * 1024 * 1024);
        require(reversedProgram != nullptr, "Create reversed Work Graph");

        auto* shared = mcdr_dx12CreateBuffer(1024 | 4 | 8, inputBytes);
        auto* sharedUpload = mcdr_dx12CreateBuffer(2 | 4, inputBytes);
        auto* firstOutput = mcdr_dx12CreateBuffer(1024 | 4 | 8, outputBytes);
        auto* secondOutput = mcdr_dx12CreateBuffer(1024 | 4 | 8, outputBytes);
        auto* outputUpload = mcdr_dx12CreateBuffer(2 | 4, outputBytes);
        auto* readback = mcdr_dx12CreateBuffer(1 | 8, outputBytes * 2);
        require(shared != nullptr && sharedUpload != nullptr && firstOutput != nullptr &&
            secondOutput != nullptr && outputUpload != nullptr && readback != nullptr, "Create buffers");

        int64_t mappedBytes = 0;
        void* mapped = mcdr_dx12MapBuffer(sharedUpload, 0, inputBytes, 0, 1, &mappedBytes);
        require(mapped != nullptr && mappedBytes >= inputBytes, "Map shared inputs");
        std::memset(mapped, 0, static_cast<size_t>(inputBytes));
        std::memcpy(mapped, &cbvValue, valueBytes);
        std::memcpy(static_cast<uint8_t*>(mapped) + valueBytes * 64, &srvValue, valueBytes);
        mcdr_dx12UnmapBuffer(sharedUpload);
        mapped = mcdr_dx12MapBuffer(outputUpload, 0, outputBytes, 0, 1, &mappedBytes);
        require(mapped != nullptr && mappedBytes >= outputBytes, "Map output sentinel");
        for (size_t index = 0; index < static_cast<size_t>(outputBytes / valueBytes); ++index)
            std::memcpy(static_cast<uint8_t*>(mapped) + index * valueBytes, &sentinel, valueBytes);
        mcdr_dx12UnmapBuffer(outputUpload);

        auto* abandoned = MCDR_D3D12_create_command_encoder();
        require(abandoned != nullptr, "Create abandoned context");
        beginRecording(abandoned);
        dispatchValue(abandoned, normalProgram, shared, firstOutput, 0, 999, false);
        mcdr_dx12EndCommandList(abandoned);
        mcdr_dx12DestroyCommandEncoder(abandoned);

        auto* first = MCDR_D3D12_create_command_encoder();
        auto* second = MCDR_D3D12_create_command_encoder();
        require(first != nullptr && second != nullptr, "Create recording contexts");
        beginRecording(first);
        copyBuffer(first, sharedUpload, shared, inputBytes);
        copyBuffer(first, outputUpload, firstOutput, outputBytes);
        copyBuffer(first, outputUpload, secondOutput, outputBytes);
        const uint64_t emptyFence = submitRecording(first);
        waitForRecording(first, emptyFence);

        beginRecording(first);
        alignas(16) std::array<uint8_t, 16> invalidRecord{};
        const std::array<mcdr_object*, 4> validBuffers{shared, shared, firstOutput, firstOutput};
        const std::array<uint64_t, 4> validOffsets{0, valueBytes * 64, 0, 0};
        const std::array<uint64_t, 4> validLengths{256, valueBytes, outputBytes, outputBytes};
        expectRejected(dispatchRaw(first, normalProgram, validBuffers, validOffsets, validLengths,
            nullptr, 1, 8), "Null CPU records");
        expectRejected(dispatchRaw(first, normalProgram, validBuffers, validOffsets, validLengths,
            invalidRecord.data(), 1, 4), "Undersized CPU record stride");
        expectRejected(dispatchRaw(first, normalProgram, validBuffers, validOffsets, validLengths,
            invalidRecord.data(), 1, 10), "Misaligned CPU record stride");
        const std::array<mcdr_object*, 4> cbvSrvUavAlias{shared, shared, shared, shared};
        const std::array<uint64_t, 4> cbvSrvUavOffsets{0, valueBytes * 64, 512, 768};
        const std::array<uint64_t, 4> cbvSrvUavLengths{256, valueBytes, valueBytes, valueBytes};
        expectRejected(dispatchRaw(first, normalProgram, cbvSrvUavAlias, cbvSrvUavOffsets,
            cbvSrvUavLengths, invalidRecord.data(), 1, 8), "CBV/SRV versus UAV alias on disjoint spans");
        const std::array<mcdr_object*, 4> reversedAlias{shared, shared, shared, shared};
        const std::array<uint64_t, 4> reversedAliasOffsets{valueBytes * 64, 0, 512, 768};
        expectRejected(dispatchRaw(first, reversedProgram, reversedAlias, reversedAliasOffsets,
            cbvSrvUavLengths, invalidRecord.data(), 1, 8), "Reversed CBV/SRV versus UAV alias on disjoint spans");
        copyBuffer(first, firstOutput, readback, outputBytes);
        const uint64_t rejectedFence = submitRecording(first);
        waitForRecording(first, rejectedFence);
        expectSentinel(readback, outputBytes);

        beginRecording(first);
        for (uint32_t index = 0; index < dispatchesPerContext; ++index)
            dispatchValue(first, normalProgram, shared, firstOutput, index * valueBytes, 1000 + index, false);
        copyBuffer(first, firstOutput, readback, outputBytes);
        beginRecording(second);
        for (uint32_t index = 0; index < dispatchesPerContext; ++index)
            dispatchValue(second, reversedProgram, shared, secondOutput, index * valueBytes, 2000 + index, true);
        require(mcdr_dx12CopyBufferChecked(second, secondOutput, 0, readback, outputBytes, outputBytes) == 1,
            "Record reversed output readback");
        mcdr_dx12DestroyWorkGraph(reversedProgram);
        mcdr_dx12DestroyWorkGraph(normalProgram);
        const uint64_t firstFence = submitRecording(first);
        const uint64_t secondFence = submitRecording(second);
        mcdr_dx12BeginCommandListWithWait(second, 0);
        require(mcdr_dx12IsListOpen(second) == 1, "Reused submitted slot state");
        mcdr_dx12EndCommandList(second);
        require(mcdr_dx12IsListOpen(second) == 0, "End reused submitted slot state");
        waitForRecording(first, firstFence);
        waitForRecording(second, secondFence);

        std::vector<uint32_t> normalExpected(dispatchesPerContext);
        std::vector<uint32_t> reversedExpected(dispatchesPerContext);
        for (uint32_t index = 0; index < dispatchesPerContext; ++index) {
            normalExpected[index] = cbvValue + srvValue + 1000 + index;
            reversedExpected[index] = cbvValue + srvValue + 2000 + index;
        }
        verifyWords(readback, 0, normalExpected);
        verifyWords(readback, outputBytes, reversedExpected);

        mcdr_dx12DestroyCommandEncoder(second);
        mcdr_dx12DestroyCommandEncoder(first);
        mcdr_dx12DestroyResource(readback);
        mcdr_dx12DestroyResource(outputUpload);
        mcdr_dx12DestroyResource(secondOutput);
        mcdr_dx12DestroyResource(firstOutput);
        mcdr_dx12DestroyResource(sharedUpload);
        mcdr_dx12DestroyResource(shared);
        require(mcdr_dx12DestroyDeviceChecked() == 1, "Destroy device");
        std::cout << "PASS: CBV/SRV reads from one DEFAULT resource in both root binding orders, duplicate UAV binding, "
            "disjoint CBV/SRV-UAV alias rejection, null/undersized/misaligned CPU records, discarded initialization, "
            "two pending contexts, program release before submit, and submitted-slot reuse with zero extra wait\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        if (mcdr_dx12DestroyDeviceChecked() != 1) std::cerr << "Device cleanup: " << lastError() << '\n';
        return 1;
    }
}
