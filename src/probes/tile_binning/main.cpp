#define NOMINMAX
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <array>
#include <bit>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" {
__declspec(dllexport) extern const UINT D3D12SDKVersion = 619;
__declspec(dllexport) extern const char* D3D12SDKPath = ".\\";
}

using Microsoft::WRL::ComPtr;
using Words = std::vector<uint32_t>;
constexpr uint32_t maximumPrimitives = 4096;
constexpr uint32_t untouched = 0xa5c39e71;
enum class MaskPattern { Mixed, Empty, AllTiles, AllBits, LastOnly };
enum class PrefixOutcome { Success, InvalidBatch, ExistingError, Overflow };

void require(HRESULT result, const char* operation) {
    if (FAILED(result)) throw std::runtime_error(std::string(operation) + " HRESULT=" + std::to_string(uint32_t(result)));
}

struct PrefixCase {
    std::string name;
    uint32_t primitiveCount;
    uint32_t residentCount = 16;
    MaskPattern pattern = MaskPattern::Mixed;
    PrefixOutcome outcome = PrefixOutcome::Success;
    uint32_t capacity = UINT32_MAX;
    uint32_t initialError = 0;
    uint32_t schema = 1;
    uint32_t flags = 0;
    uint32_t kind = 1;
    uint32_t stride = 28;
};

struct PrefixResult {
    PrefixCase input;
    Words masks;
    ComPtr<ID3D12Resource> readback;
};

struct Gpu {
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence;
    ComPtr<ID3D12RootSignature> root;
    ComPtr<ID3D12PipelineState> prefix;
    std::vector<ComPtr<ID3D12Resource>> resources;

    explicit Gpu(const std::filesystem::path& directory) {
        ComPtr<ID3D12SDKConfiguration1> configuration;
        require(D3D12GetInterface(CLSID_D3D12SDKConfiguration, IID_PPV_ARGS(&configuration)), "GetSDKConfiguration");
        ComPtr<ID3D12DeviceFactory> factory;
        const auto runtimeDirectory = directory.string() + "\\";
        require(configuration->CreateDeviceFactory(619, runtimeDirectory.c_str(), IID_PPV_ARGS(&factory)), "CreateDeviceFactory");
        ComPtr<IDXGIFactory6> adapters;
        require(CreateDXGIFactory2(0, IID_PPV_ARGS(&adapters)), "CreateDXGIFactory");
        ComPtr<IDXGIAdapter1> adapter;
        require(adapters->EnumAdapterByGpuPreference(0, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE, IID_PPV_ARGS(&adapter)), "SelectAdapter");
        DXGI_ADAPTER_DESC1 adapterDescription{};
        require(adapter->GetDesc1(&adapterDescription), "AdapterDescription");
        require(factory->CreateDevice(adapter.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device)), "CreateDevice");
        D3D12_FEATURE_DATA_SHADER_MODEL shaderModel{static_cast<D3D_SHADER_MODEL>(0x69)};
        require(device->CheckFeatureSupport(D3D12_FEATURE_SHADER_MODEL, &shaderModel, sizeof(shaderModel)), "ShaderModel");
        if (shaderModel.HighestShaderModel < static_cast<D3D_SHADER_MODEL>(0x69))
            throw std::runtime_error("Shader model below 6.9");
        std::cout << "adapter-vendor=" << adapterDescription.VendorId << " device=" << adapterDescription.DeviceId
            << " shader-model=" << shaderModel.HighestShaderModel << '\n';
        D3D12_COMMAND_QUEUE_DESC queueDescription{};
        require(device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&queue)), "CreateQueue");
        require(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator)), "CreateAllocator");
        require(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator.Get(), nullptr, IID_PPV_ARGS(&list)), "CreateList");
        require(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)), "CreateFence");
        std::array<D3D12_ROOT_PARAMETER, 8> roots{};
        for (UINT index = 0; index < 3; ++index) {
            roots[index].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
            roots[index].Descriptor = {index, 3};
        }
        roots[3].ParameterType = D3D12_ROOT_PARAMETER_TYPE_SRV;
        roots[3].Descriptor = {8, 3};
        for (UINT index = 0; index < 4; ++index) {
            roots[index + 4].ParameterType = D3D12_ROOT_PARAMETER_TYPE_UAV;
            roots[index + 4].Descriptor = {index, 3};
        }
        D3D12_ROOT_SIGNATURE_DESC description{};
        description.NumParameters = static_cast<UINT>(roots.size());
        description.pParameters = roots.data();
        ComPtr<ID3DBlob> serialized, diagnostic;
        require(D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1, &serialized, &diagnostic), "SerializeRoot");
        require(device->CreateRootSignature(0, serialized->GetBufferPointer(), serialized->GetBufferSize(), IID_PPV_ARGS(&root)), "CreateRoot");
        std::ifstream input(directory / "PrefixTileRefsV1.dxil", std::ios::binary | std::ios::ate);
        if (!input) throw std::runtime_error("Missing PrefixTileRefsV1.dxil");
        const auto length = input.tellg();
        if (length <= 0) throw std::runtime_error("Empty PrefixTileRefsV1.dxil");
        std::vector<char> bytes(static_cast<size_t>(length));
        input.seekg(0);
        if (!input.read(bytes.data(), static_cast<std::streamsize>(bytes.size()))) throw std::runtime_error("Read DXIL failed");
        D3D12_COMPUTE_PIPELINE_STATE_DESC pipeline{};
        pipeline.pRootSignature = root.Get();
        pipeline.CS = {bytes.data(), bytes.size()};
        require(device->CreateComputePipelineState(&pipeline, IID_PPV_ARGS(&prefix)), "CreatePrefixPipeline");
        list->SetComputeRootSignature(root.Get());
        list->SetPipelineState(prefix.Get());
    }

    ComPtr<ID3D12Resource> createBuffer(uint64_t bytes, D3D12_HEAP_TYPE heapType,
        D3D12_RESOURCE_STATES state, D3D12_RESOURCE_FLAGS flags = D3D12_RESOURCE_FLAG_NONE) {
        D3D12_HEAP_PROPERTIES heap{};
        heap.Type = heapType;
        D3D12_RESOURCE_DESC description{};
        description.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
        description.Width = bytes;
        description.Height = 1;
        description.DepthOrArraySize = 1;
        description.MipLevels = 1;
        description.SampleDesc.Count = 1;
        description.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        description.Flags = flags;
        ComPtr<ID3D12Resource> resource;
        require(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description, state, nullptr,
            IID_PPV_ARGS(&resource)), "CreateBuffer");
        resources.push_back(resource);
        return resource;
    }

    ComPtr<ID3D12Resource> uploadWords(const Words& words) {
        auto upload = createBuffer(words.size() * sizeof(uint32_t), D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
        void* mapped = nullptr;
        const D3D12_RANGE empty{0, 0};
        require(upload->Map(0, &empty, &mapped), "MapUpload");
        std::memcpy(mapped, words.data(), words.size() * sizeof(uint32_t));
        upload->Unmap(0, nullptr);
        return upload;
    }

    void transition(ID3D12Resource* resource, D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after) {
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {resource, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, before, after};
        list->ResourceBarrier(1, &barrier);
    }

    ComPtr<ID3D12Resource> uploadUav(const Words& words) {
        auto upload = uploadWords(words);
        auto buffer = createBuffer(words.size() * sizeof(uint32_t), D3D12_HEAP_TYPE_DEFAULT,
            D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS);
        list->CopyBufferRegion(buffer.Get(), 0, upload.Get(), 0, words.size() * sizeof(uint32_t));
        transition(buffer.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        return buffer;
    }

    PrefixResult recordPrefix(const PrefixCase& input) {
        Words masks(maximumPrimitives, 0xdeadbeef);
        for (uint32_t primitive = 0; primitive < input.primitiveCount && primitive < maximumPrimitives; ++primitive) {
            switch (input.pattern) {
            case MaskPattern::Mixed: masks[primitive] = (primitive * 40503u ^ (primitive >> 2u) ^ 0x1234u) & 0xffffu; break;
            case MaskPattern::Empty: masks[primitive] = 0; break;
            case MaskPattern::AllTiles: masks[primitive] = 0xffffu; break;
            case MaskPattern::AllBits: masks[primitive] = UINT32_MAX; break;
            case MaskPattern::LastOnly: masks[primitive] = primitive + 1 == input.primitiveCount ? 0xffffu : 0; break;
            }
        }
        Words batch(128);
        batch[0] = input.schema;
        batch[1] = input.residentCount;
        batch[3] = input.flags;
        auto batchConstants = uploadWords(batch);
        Words binning(64);
        binning[0] = input.primitiveCount;
        binning[1] = input.capacity == UINT32_MAX ? input.primitiveCount * input.residentCount : input.capacity;
        binning[2] = input.kind;
        binning[3] = input.stride;
        auto binningConstants = uploadWords(binning);
        auto dummyConstants = uploadWords(Words(64));
        auto maskBuffer = uploadUav(masks);
        auto prefixBuffer = uploadUav(Words(maximumPrimitives + 2, untouched));
        auto commandBuffer = uploadUav(Words(4, untouched));
        auto controlBuffer = uploadUav(Words{untouched, input.initialError, 0x13579bdf, 0x2468ace0});
        list->SetComputeRootConstantBufferView(0, dummyConstants->GetGPUVirtualAddress());
        list->SetComputeRootConstantBufferView(1, batchConstants->GetGPUVirtualAddress());
        list->SetComputeRootConstantBufferView(2, binningConstants->GetGPUVirtualAddress());
        list->SetComputeRootShaderResourceView(3, dummyConstants->GetGPUVirtualAddress());
        list->SetComputeRootUnorderedAccessView(4, maskBuffer->GetGPUVirtualAddress());
        list->SetComputeRootUnorderedAccessView(5, prefixBuffer->GetGPUVirtualAddress() + sizeof(uint32_t));
        list->SetComputeRootUnorderedAccessView(6, commandBuffer->GetGPUVirtualAddress());
        list->SetComputeRootUnorderedAccessView(7, controlBuffer->GetGPUVirtualAddress());
        list->Dispatch(1, 1, 1);
        transition(prefixBuffer.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
        transition(controlBuffer.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
        auto readback = createBuffer((maximumPrimitives + 6) * sizeof(uint32_t), D3D12_HEAP_TYPE_READBACK,
            D3D12_RESOURCE_STATE_COPY_DEST);
        list->CopyBufferRegion(readback.Get(), 0, prefixBuffer.Get(), 0, (maximumPrimitives + 2) * sizeof(uint32_t));
        list->CopyBufferRegion(readback.Get(), (maximumPrimitives + 2) * sizeof(uint32_t), controlBuffer.Get(), 0,
            4 * sizeof(uint32_t));
        return {input, std::move(masks), std::move(readback)};
    }

    void submitAndWait() {
        require(list->Close(), "CloseList");
        ID3D12CommandList* lists[]{list.Get()};
        queue->ExecuteCommandLists(1, lists);
        require(queue->Signal(fence.Get(), 1), "Signal");
        const HANDLE completed = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!completed) throw std::runtime_error("CreateEvent failed");
        const HRESULT eventResult = fence->SetEventOnCompletion(1, completed);
        const DWORD waitResult = SUCCEEDED(eventResult) ? WaitForSingleObject(completed, 30000) : WAIT_FAILED;
        CloseHandle(completed);
        require(eventResult, "SetEventOnCompletion");
        if (waitResult != WAIT_OBJECT_0 || fence->GetCompletedValue() == UINT64_MAX)
            throw std::runtime_error("GPU fence failed; device status=" + std::to_string(uint32_t(device->GetDeviceRemovedReason())));
    }
};

void checkPrefix(const PrefixResult& result) {
    Words expected(maximumPrimitives + 6, untouched);
    const auto& input = result.input;
    uint32_t count = 0;
    if (input.outcome == PrefixOutcome::Success || input.outcome == PrefixOutcome::Overflow) {
        for (uint32_t primitive = 0; primitive < input.primitiveCount; ++primitive) {
            expected[primitive + 1] = count;
            count += std::popcount(result.masks[primitive]);
        }
    }
    const size_t control = maximumPrimitives + 2;
    expected[control] = input.outcome == PrefixOutcome::Success ? count : 0;
    expected[control + 1] = input.outcome == PrefixOutcome::Overflow ? 8 : input.initialError;
    expected[control + 2] = 0x13579bdf;
    expected[control + 3] = 0x2468ace0;
    void* mapped = nullptr;
    const D3D12_RANGE range{0, expected.size() * sizeof(uint32_t)};
    require(result.readback->Map(0, &range, &mapped), "MapReadback");
    Words actual(expected.size());
    std::memcpy(actual.data(), mapped, actual.size() * sizeof(uint32_t));
    const D3D12_RANGE empty{0, 0};
    result.readback->Unmap(0, &empty);
    for (size_t word = 0; word < expected.size(); ++word) {
        if (actual[word] != expected[word]) throw std::runtime_error(input.name + " word=" + std::to_string(word) +
            " expected=" + std::to_string(expected[word]) + " actual=" + std::to_string(actual[word]));
    }
    std::cout << "PASS " << input.name << " primitives=" << input.primitiveCount << " sum=" << count
        << " control=" << actual[control] << ',' << actual[control + 1];
    if (input.primitiveCount > 0 && input.primitiveCount <= maximumPrimitives)
        std::cout << " last-prefix=" << actual[input.primitiveCount];
    std::cout << " checked-words=" << expected.size() << '\n';
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Usage: tile-binning.exe report-directory");
        Gpu gpu(argv[1]);
        std::vector<PrefixCase> cases;
        for (const uint32_t count : {0u, 1u, 3u, 4u, 5u, 63u, 64u, 65u, 1023u, 1024u, 1025u, 4095u, 4096u})
            cases.push_back({"mixed-" + std::to_string(count), count});
        cases.push_back({"all-tiles-exact-capacity", 4096, 16, MaskPattern::AllTiles});
        cases.push_back({"empty-masks", 4096, 16, MaskPattern::Empty});
        cases.push_back({"last-only-4095", 4095, 16, MaskPattern::LastOnly});
        cases.push_back({"last-only-4096", 4096, 16, MaskPattern::LastOnly});
        PrefixCase indexed{"indexed-stride-36", 4096};
        indexed.kind = 0;
        indexed.stride = 36;
        cases.push_back(indexed);
        PrefixCase error{"existing-error", 4096};
        error.outcome = PrefixOutcome::ExistingError;
        error.initialError = 6;
        cases.push_back(error);
        PrefixCase capacity{"invalid-capacity", 4096};
        capacity.outcome = PrefixOutcome::InvalidBatch;
        capacity.capacity = 65535;
        cases.push_back(capacity);
        PrefixCase schema{"invalid-schema", 4096};
        schema.outcome = PrefixOutcome::InvalidBatch;
        schema.schema = 2;
        cases.push_back(schema);
        PrefixCase flags{"invalid-flags", 4096};
        flags.outcome = PrefixOutcome::InvalidBatch;
        flags.flags = 1;
        cases.push_back(flags);
        PrefixCase resident{"invalid-resident-count", 4096, 17};
        resident.outcome = PrefixOutcome::InvalidBatch;
        cases.push_back(resident);
        PrefixCase primitives{"invalid-primitive-count", 4097};
        primitives.outcome = PrefixOutcome::InvalidBatch;
        cases.push_back(primitives);
        PrefixCase stride{"invalid-stride", 4096};
        stride.outcome = PrefixOutcome::InvalidBatch;
        stride.stride = 36;
        cases.push_back(stride);
        cases.push_back({"overflow-all-32-mask-bits", 4096, 16, MaskPattern::AllBits, PrefixOutcome::Overflow});
        std::vector<PrefixResult> results;
        for (const auto& input : cases) results.push_back(gpu.recordPrefix(input));
        gpu.submitAndWait();
        for (const auto& result : results) checkPrefix(result);
        std::cout << "tile-prefix-gpu-cases=" << results.size() << " readback-bytes="
            << results.size() * (maximumPrimitives + 6) * sizeof(uint32_t) << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
