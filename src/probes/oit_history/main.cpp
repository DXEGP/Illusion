#define NOMINMAX
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "mcdr_oit_shader.h"

extern "C" {
__declspec(dllexport) extern const UINT D3D12SDKVersion = 619;
__declspec(dllexport) extern const char* D3D12SDKPath = ".\\";
}
using Microsoft::WRL::ComPtr;
using Words = std::vector<uint32_t>;
constexpr UINT width = 3, height = 3, layers = 2, recordCount = width * height * layers;
constexpr UINT recordWords = mcdr::oit_shader::recordStride / 4;

void require(HRESULT result, const char* operation) {
    if (FAILED(result)) throw std::runtime_error(std::string(operation) + " HRESULT=" + std::to_string(uint32_t(result)));
}
uint32_t bits(float value) { return std::bit_cast<uint32_t>(value); }
float halfFloat(uint16_t value) {
    const uint32_t exponent = (value >> 10) & 31;
    if (!exponent) return std::ldexp(float(value & 1023), -24) * (value & 32768 ? -1.0f : 1.0f);
    return std::bit_cast<float>((uint32_t(value & 32768) << 16) | ((exponent + 112) << 23) | (uint32_t(value & 1023) << 13));
}

struct Gpu {
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence;
    ComPtr<ID3D12RootSignature> root;
    ComPtr<ID3D12PipelineState> initialize, compact, resolve, append;
    ComPtr<ID3D12DescriptorHeap> descriptors;
    UINT increment = 0;
    uint64_t serial = 0;
    HANDLE completed = CreateEventW(nullptr, FALSE, FALSE, nullptr);
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
        DXGI_ADAPTER_DESC1 adapterDescription{}; require(adapter->GetDesc1(&adapterDescription), "AdapterDescription");
        require(factory->CreateDevice(adapter.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device)), "CreateDevice");
        D3D12_FEATURE_DATA_SHADER_MODEL shaderModel{static_cast<D3D_SHADER_MODEL>(0x69)};
        require(device->CheckFeatureSupport(D3D12_FEATURE_SHADER_MODEL, &shaderModel, sizeof(shaderModel)), "ShaderModel");
        if (shaderModel.HighestShaderModel < static_cast<D3D_SHADER_MODEL>(0x69))
            throw std::runtime_error("Runtime shader model=" + std::to_string(shaderModel.HighestShaderModel) +
                " vendor=" + std::to_string(adapterDescription.VendorId) + " below 6.9");
        D3D12_COMMAND_QUEUE_DESC queueDescription{};
        require(device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&queue)), "CreateQueue");
        require(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator)), "CreateAllocator");
        require(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator.Get(), nullptr, IID_PPV_ARGS(&list)), "CreateList");
        require(list->Close(), "CloseInitialList");
        require(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)), "CreateFence");
        D3D12_DESCRIPTOR_HEAP_DESC heap{};
        heap.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV; heap.NumDescriptors = 14; heap.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
        require(device->CreateDescriptorHeap(&heap, IID_PPV_ARGS(&descriptors)), "CreateDescriptorHeap");
        increment = device->GetDescriptorHandleIncrementSize(heap.Type);
        D3D12_ROOT_PARAMETER roots[6]{};
        D3D12_DESCRIPTOR_RANGE ranges[5]{};
        roots[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV; roots[0].Descriptor = {0, 2};
        const UINT starts[]{0, 0, 3, 3, 5}, counts[]{3, 3, 2, 2, 4};
        for (UINT index = 0; index < 5; ++index) {
            ranges[index].RangeType = index % 2 ? D3D12_DESCRIPTOR_RANGE_TYPE_UAV : D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
            ranges[index].NumDescriptors = counts[index]; ranges[index].BaseShaderRegister = starts[index]; ranges[index].RegisterSpace = 2;
            roots[index + 1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE; roots[index + 1].DescriptorTable = {1, &ranges[index]};
        }
        D3D12_ROOT_SIGNATURE_DESC description{}; description.NumParameters = 6; description.pParameters = roots;
        ComPtr<ID3DBlob> serialized, diagnostic;
        require(D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1, &serialized, &diagnostic), "SerializeRoot");
        require(device->CreateRootSignature(0, serialized->GetBufferPointer(), serialized->GetBufferSize(), IID_PPV_ARGS(&root)), "CreateRoot");
        for (const auto& entry : {std::pair{"InitializeRecordsV1", std::addressof(initialize)},
            std::pair{"CompactRecordsV1", std::addressof(compact)},
            std::pair{"ResolveRgbHistoryV3", std::addressof(resolve)},
            std::pair{"AppendPackedRecords", std::addressof(append)}}) {
            std::ifstream input(directory / (std::string(entry.first) + ".dxil"), std::ios::binary | std::ios::ate);
            if (!input) throw std::runtime_error("Missing DXIL");
            std::vector<char> bytes(static_cast<size_t>(input.tellg())); input.seekg(0); input.read(bytes.data(), bytes.size());
            D3D12_COMPUTE_PIPELINE_STATE_DESC pipeline{}; pipeline.pRootSignature = root.Get(); pipeline.CS = {bytes.data(), bytes.size()};
            require(device->CreateComputePipelineState(&pipeline, IID_PPV_ARGS(entry.second->GetAddressOf())), entry.first);
        }
    }
    ~Gpu() { CloseHandle(completed); }
    ID3D12Resource* createBuffer(uint64_t bytes, D3D12_HEAP_TYPE heapType, D3D12_RESOURCE_STATES state, D3D12_RESOURCE_FLAGS flags = D3D12_RESOURCE_FLAG_NONE) {
        D3D12_HEAP_PROPERTIES heap{}; heap.Type = heapType;
        D3D12_RESOURCE_DESC description{};
        description.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER; description.Width = bytes; description.Height = 1;
        description.DepthOrArraySize = 1; description.MipLevels = 1; description.SampleDesc.Count = 1;
        description.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR; description.Flags = flags;
        ComPtr<ID3D12Resource> resource;
        require(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description, state, nullptr, IID_PPV_ARGS(&resource)), "CreateBuffer");
        auto* result = resource.Get(); resources.push_back(std::move(resource)); return result;
    }
    ID3D12Resource* createTexture(DXGI_FORMAT format, D3D12_RESOURCE_FLAGS flags, D3D12_RESOURCE_STATES state) {
        D3D12_HEAP_PROPERTIES heap{}; heap.Type = D3D12_HEAP_TYPE_DEFAULT;
        D3D12_RESOURCE_DESC description{};
        description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D; description.Width = width; description.Height = height;
        description.DepthOrArraySize = 1; description.MipLevels = 1; description.SampleDesc.Count = 1;
        description.Format = format; description.Flags = flags;
        ComPtr<ID3D12Resource> resource;
        require(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description, state, nullptr, IID_PPV_ARGS(&resource)), "CreateTexture");
        auto* result = resource.Get(); resources.push_back(std::move(resource)); return result;
    }
    void write(ID3D12Resource* resource, const void* contents, size_t bytes) {
        void* mapped = nullptr; D3D12_RANGE empty{0, 0}; require(resource->Map(0, &empty, &mapped), "MapUpload");
        std::memcpy(mapped, contents, bytes); resource->Unmap(0, nullptr);
    }
    void transition(ID3D12Resource* resource, D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after) {
        D3D12_RESOURCE_BARRIER barrier{}; barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {resource, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, before, after}; list->ResourceBarrier(1, &barrier);
    }
    void barrier(ID3D12Resource* resource) {
        D3D12_RESOURCE_BARRIER barrier{}; barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_UAV;
        barrier.UAV.pResource = resource; list->ResourceBarrier(1, &barrier);
    }
    D3D12_CPU_DESCRIPTOR_HANDLE descriptor(UINT index) {
        auto handle = descriptors->GetCPUDescriptorHandleForHeapStart(); handle.ptr += uint64_t(index) * increment; return handle;
    }
    void bindRecordDescriptors(ID3D12Resource* constants) {
        ID3D12DescriptorHeap* heaps[]{descriptors.Get()}; list->SetDescriptorHeaps(1, heaps);
        list->SetComputeRootSignature(root.Get()); list->SetComputeRootConstantBufferView(0, constants->GetGPUVirtualAddress());
        const UINT offsets[]{0, 3, 6, 8, 10};
        for (UINT index = 0; index < 5; ++index) {
            auto handle = descriptors->GetGPUDescriptorHandleForHeapStart(); handle.ptr += uint64_t(offsets[index]) * increment;
            list->SetComputeRootDescriptorTable(index + 1, handle);
        }
    }
    ID3D12Resource* uploadRaw(const Words& words, UINT index, D3D12_DESCRIPTOR_RANGE_TYPE access) {
        const UINT bytes = UINT(words.size() * sizeof(UINT));
        auto* buffer = createBuffer(bytes, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
        write(buffer, words.data(), bytes);
        if (access == D3D12_DESCRIPTOR_RANGE_TYPE_UAV) {
            auto* destination = createBuffer(bytes, D3D12_HEAP_TYPE_DEFAULT, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS);
            list->CopyBufferRegion(destination, 0, buffer, 0, bytes); buffer = destination;
            transition(buffer, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            D3D12_UNORDERED_ACCESS_VIEW_DESC view{}; view.Format = DXGI_FORMAT_R32_TYPELESS; view.ViewDimension = D3D12_UAV_DIMENSION_BUFFER;
            view.Buffer.NumElements = bytes / 4; view.Buffer.Flags = D3D12_BUFFER_UAV_FLAG_RAW;
            device->CreateUnorderedAccessView(buffer, nullptr, &view, descriptor(index));
        } else {
            D3D12_SHADER_RESOURCE_VIEW_DESC view{}; view.Format = DXGI_FORMAT_R32_TYPELESS; view.ViewDimension = D3D12_SRV_DIMENSION_BUFFER;
            view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
            view.Buffer.NumElements = bytes / 4; view.Buffer.Flags = D3D12_BUFFER_SRV_FLAG_RAW;
            device->CreateShaderResourceView(buffer, &view, descriptor(index));
        }
        return buffer;
    }
    void textureView(ID3D12Resource* resource, UINT index, DXGI_FORMAT format, D3D12_DESCRIPTOR_RANGE_TYPE access) {
        if (access == D3D12_DESCRIPTOR_RANGE_TYPE_UAV) {
            D3D12_UNORDERED_ACCESS_VIEW_DESC view{}; view.Format = format; view.ViewDimension = D3D12_UAV_DIMENSION_TEXTURE2D;
            device->CreateUnorderedAccessView(resource, nullptr, &view, descriptor(index));
        } else {
            D3D12_SHADER_RESOURCE_VIEW_DESC view{}; view.Format = format; view.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
            view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING; view.Texture2D.MipLevels = 1;
            device->CreateShaderResourceView(resource, &view, descriptor(index));
        }
    }
    D3D12_TEXTURE_COPY_LOCATION transfer(ID3D12Resource* buffer, DXGI_FORMAT format) {
        D3D12_TEXTURE_COPY_LOCATION location{}; location.pResource = buffer; location.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        location.PlacedFootprint.Footprint = {format, width, height, 1, 256}; return location;
    }
    D3D12_TEXTURE_COPY_LOCATION image(ID3D12Resource* texture) {
        D3D12_TEXTURE_COPY_LOCATION location{}; location.pResource = texture; location.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX; return location;
    }
    void submitAndWait() {
        require(list->Close(), "Close"); ID3D12CommandList* lists[]{list.Get()}; queue->ExecuteCommandLists(1, lists);
        require(queue->Signal(fence.Get(), ++serial), "Signal"); require(fence->SetEventOnCompletion(serial, completed), "SetEvent");
        if (WaitForSingleObject(completed, 30000) != WAIT_OBJECT_0) throw std::runtime_error("GPU fence did not complete");
    }
};

Words records(bool history) {
    Words result(recordCount * recordWords);
    for (UINT pixel = 0; pixel < width * height; ++pixel) {
        for (UINT layer = 0; layer < layers; ++layer) {
            const UINT index = pixel * layers + layer, address = index * recordWords;
            result[address] = layer + 1 < layers ? index + 1 : UINT32_MAX;
            result[address + 1] = pixel; result[address + 2] = layer;
            const float material = pixel == 4 ? (layer ? 1.0f : 0.5f) : (pixel % 2 ? 1.0f : 0.0f);
            for (UINT channel = 0; channel < 3; ++channel) {
                result[address + 4 + channel] = bits(material);
                result[address + 28 + channel] = bits(history ? (layer ? 0.875f : 0.75f) : material);
            }
            result[address + 7] = bits(layer ? 0.2f : 0.4f);
            result[address + 8] = bits(0.5f); result[address + 9] = bits(1.0f); result[address + 10] = 15;
            result[address + 11] = bits(0.5f);
            const float coordinateX = (float(pixel % width) + 0.5f) / width, coordinateY = (float(pixel / width) + 0.5f) / height;
            result[address + 12] = bits(coordinateX); result[address + 13] = bits(coordinateY);
            result[address + 14] = bits(1.0f / width); result[address + 17] = bits(1.0f / height);
            result[address + 18] = bits(coordinateX); result[address + 19] = bits(coordinateY);
            result[address + 20] = bits(coordinateX); result[address + 21] = bits(coordinateY);
            result[address + 22] = bits(0.5f); result[address + 23] = bits(layer ? 0.0f : 0.25f);
            result[address + 24] = bits(layer ? 1.0f : 0.8f); result[address + 25] = bits(layer ? 0.4f : 0.5f);
            result[address + 31] = bits(1.0f); result[address + 32] = bits(1.0f / width); result[address + 35] = bits(1.0f / height);
            if (history) {
                result[address + 7] = bits(0.01f);
                result[address + 23] = bits(0.9f); result[address + 24] = bits(0.1f); result[address + 25] = bits(0.01f);
            }
        }
    }
    return result;
}

struct Scenario {
    const char* name;
    UINT status = 0;
    bool empty = false, ownerMismatch = false, withHistory = true, moving = false;
    float expected = 0.2f;
};

void runCase(Gpu& gpu, const Scenario& scenario) {
    require(gpu.allocator->Reset(), "ResetAllocator"); require(gpu.list->Reset(gpu.allocator.Get(), nullptr), "ResetList");
    auto currentRecords = records(false), previousRecords = records(true);
    Words pixels(width * height * 2), previousPixels(width * height * 2);
    for (UINT pixel = 0; pixel < width * height; ++pixel) {
        pixels[pixel * 2] = scenario.empty ? UINT32_MAX : pixel * layers;
        pixels[pixel * 2 + 1] = (scenario.empty ? 0 : layers) | (scenario.status << 8);
        previousPixels[pixel * 2] = pixel * layers; previousPixels[pixel * 2 + 1] = layers;
    }
    if (scenario.moving) {
        for (UINT layer = 0; layer < layers; ++layer) {
            const UINT address = (4 * layers + layer) * recordWords;
            currentRecords[address + 18] = bits(0.5f / width); currentRecords[address + 20] = bits(0.5f / width);
            currentRecords[address + 12] = bits(0.5f / width);
        }
        previousPixels[8] = UINT32_MAX; previousPixels[9] = 0;
    }
    Words sources{1, 0, 1, 0, 1, 0, 0, 15}, previousSources = sources;
    if (scenario.ownerMismatch) previousSources[0] = 99;
    Words primitives(layers * 14), previousTriangles(layers * 20);
    for (UINT layer = 0; layer < layers; ++layer) {
        const UINT address = layer * 14;
        primitives[address] = layer + 1; primitives[address + 2] = 1; primitives[address + 4] = layer + 1;
        primitives[address + 6] = 1; primitives[address + 8] = 1; primitives[address + 11] = layer; primitives[address + 12] = 15;
        previousTriangles[layer * 20 + 18] = 15;
    }
    std::array<UINT, 32> constants{};
    constants[0] = width; constants[1] = height; constants[2] = recordCount; constants[3] = layers;
    constants[4] = 1; constants[5] = layers; constants[6] = layers;
    constants[24] = width; constants[25] = height; constants[26] = recordCount; constants[27] = layers;
    constants[28] = 1; constants[29] = layers; constants[31] = scenario.withHistory ? 1 : 0;
    auto* constantBuffer = gpu.createBuffer(256, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    gpu.write(constantBuffer, constants.data(), sizeof(constants));
    gpu.uploadRaw(sources, 0, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(primitives, 1, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(previousTriangles, 2, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    auto* currentRecordBuffer = gpu.uploadRaw(currentRecords, 3, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    auto* pixelBuffer = gpu.uploadRaw(pixels, 4, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    auto* indexBuffer = gpu.uploadRaw(Words(4 + recordCount), 5, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    gpu.uploadRaw(previousRecords, 10, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(previousPixels, 11, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(previousSources, 12, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(primitives, 13, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    auto* current = gpu.createTexture(DXGI_FORMAT_R16G16B16A16_FLOAT, D3D12_RESOURCE_FLAG_NONE, D3D12_RESOURCE_STATE_COPY_DEST);
    auto* coverage = gpu.createTexture(DXGI_FORMAT_R8_UNORM, D3D12_RESOURCE_FLAG_NONE, D3D12_RESOURCE_STATE_COPY_DEST);
    auto* status = gpu.createTexture(DXGI_FORMAT_R32_UINT, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    auto* corrected = gpu.createTexture(DXGI_FORMAT_R16G16B16A16_FLOAT, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    std::array<std::byte, 256 * height> bytes{};
    const std::array<uint16_t, 4> inputColor{0x3266, 0x3266, 0x3266, 0x3800};
    for (UINT row = 0; row < height; ++row)
        for (UINT column = 0; column < width; ++column) std::memcpy(bytes.data() + row * 256 + column * 8, inputColor.data(), 8);
    auto* upload = gpu.createBuffer(bytes.size(), D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    gpu.write(upload, bytes.data(), bytes.size()); auto image = gpu.image(current), transfer = gpu.transfer(upload, DXGI_FORMAT_R16G16B16A16_FLOAT);
    gpu.list->CopyTextureRegion(&image, 0, 0, 0, &transfer, nullptr);
    bytes.fill(std::byte{});
    upload = gpu.createBuffer(bytes.size(), D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ); gpu.write(upload, bytes.data(), bytes.size());
    image = gpu.image(coverage); transfer = gpu.transfer(upload, DXGI_FORMAT_R8_UNORM); gpu.list->CopyTextureRegion(&image, 0, 0, 0, &transfer, nullptr);
    gpu.transition(current, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    gpu.transition(coverage, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    gpu.textureView(current, 6, DXGI_FORMAT_R16G16B16A16_FLOAT, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.textureView(coverage, 7, DXGI_FORMAT_R8_UNORM, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.textureView(status, 8, DXGI_FORMAT_R32_UINT, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    gpu.textureView(corrected, 9, DXGI_FORMAT_R16G16B16A16_FLOAT, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    gpu.bindRecordDescriptors(constantBuffer);
    gpu.list->SetPipelineState(gpu.compact.Get()); gpu.list->Dispatch(1, 1, 1);
    for (auto* buffer : {currentRecordBuffer, pixelBuffer, indexBuffer, status}) gpu.barrier(buffer);
    gpu.list->SetPipelineState(gpu.resolve.Get()); gpu.list->Dispatch(1, 1, 1);
    auto* readback = gpu.createBuffer(bytes.size(), D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
    auto* statusReadback = gpu.createBuffer(bytes.size(), D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
    gpu.transition(corrected, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
    gpu.transition(status, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
    image = gpu.image(corrected); transfer = gpu.transfer(readback, DXGI_FORMAT_R16G16B16A16_FLOAT);
    gpu.list->CopyTextureRegion(&transfer, 0, 0, 0, &image, nullptr);
    image = gpu.image(status); transfer = gpu.transfer(statusReadback, DXGI_FORMAT_R32_UINT);
    gpu.list->CopyTextureRegion(&transfer, 0, 0, 0, &image, nullptr); gpu.submitAndWait();
    std::byte* mapped = nullptr; D3D12_RANGE range{0, bytes.size()};
    require(readback->Map(0, &range, reinterpret_cast<void**>(&mapped)), "MapReadback");
    std::array<uint16_t, 4> actual{}; std::memcpy(actual.data(), mapped + 256 + 8, 8); readback->Unmap(0, nullptr);
    require(statusReadback->Map(0, &range, reinterpret_cast<void**>(&mapped)), "MapStatus");
    UINT actualStatus = 0; std::memcpy(&actualStatus, mapped + 256 + 4, 4); statusReadback->Unmap(0, nullptr);
    if (std::abs(halfFloat(actual[0]) - scenario.expected) > 0.0002f || actual[3] != 0x3800)
        throw std::runtime_error(std::string(scenario.name) + " output=" + std::to_string(halfFloat(actual[0])) + " expected=" + std::to_string(scenario.expected));
    const bool expectedHistory = scenario.withHistory && !scenario.empty && !scenario.ownerMismatch && scenario.status == 0;
    if (((actualStatus & 256) != 0) != expectedHistory) throw std::runtime_error(std::string(scenario.name) + " unexpected status=" + std::to_string(actualStatus));
    std::cout << scenario.name << " rgb=" << halfFloat(actual[0]) << " alpha=" << halfFloat(actual[3]) << " status=" << actualStatus << '\n';
    gpu.resources.clear();
}

void runAppendCase(Gpu& gpu) {
    require(gpu.allocator->Reset(), "ResetAppendAllocator"); require(gpu.list->Reset(gpu.allocator.Get(), nullptr), "ResetAppendList");
    constexpr UINT capacity = 32;
    std::array<UINT, 32> constants{};
    constants[0] = width; constants[1] = height; constants[2] = capacity; constants[3] = capacity;
    constants[4] = 1; constants[5] = 1; constants[14] = 1;
    auto* constantBuffer = gpu.createBuffer(256, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    gpu.write(constantBuffer, constants.data(), sizeof(constants));
    gpu.uploadRaw(Words{1, 0, 1, 0, 1, 0, 0, 9}, 0, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    Words primitives(14); primitives[0] = 1; primitives[2] = 1; primitives[4] = 1;
    primitives[6] = 1; primitives[8] = 1; primitives[11] = UINT32_MAX; primitives[12] = 9;
    gpu.uploadRaw(primitives, 1, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    gpu.uploadRaw(Words(20), 2, D3D12_DESCRIPTOR_RANGE_TYPE_SRV);
    auto* recordBuffer = gpu.uploadRaw(Words(capacity * recordWords), 3, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    auto* pixelBuffer = gpu.uploadRaw(Words(width * height * 2, UINT32_MAX), 4, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    auto* allocatorBuffer = gpu.uploadRaw(Words(4 + capacity, UINT32_MAX), 5, D3D12_DESCRIPTOR_RANGE_TYPE_UAV);
    gpu.bindRecordDescriptors(constantBuffer);
    gpu.list->SetPipelineState(gpu.initialize.Get()); gpu.list->Dispatch(1, 1, 1);
    gpu.barrier(pixelBuffer); gpu.barrier(allocatorBuffer);
    gpu.list->SetPipelineState(gpu.append.Get()); gpu.list->Dispatch(2, 1, 1);
    const std::array buffers{pixelBuffer, allocatorBuffer, recordBuffer};
    const std::array<uint64_t, 3> sizes{width * height * 8, (4 + capacity) * 4, capacity * mcdr::oit_shader::recordStride};
    std::array<ID3D12Resource*, 3> readbacks{};
    for (size_t index = 0; index < buffers.size(); ++index) {
        gpu.transition(buffers[index], D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
        readbacks[index] = gpu.createBuffer(sizes[index], D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
        gpu.list->CopyBufferRegion(readbacks[index], 0, buffers[index], 0, sizes[index]);
    }
    gpu.submitAndWait();
    std::array<Words, 3> output;
    for (size_t index = 0; index < output.size(); ++index) {
        output[index].resize(size_t(sizes[index] / 4));
        void* mapped = nullptr; D3D12_RANGE range{0, SIZE_T(sizes[index])};
        require(readbacks[index]->Map(0, &range, &mapped), "MapAppendReadback");
        std::memcpy(output[index].data(), mapped, size_t(sizes[index])); readbacks[index]->Unmap(0, nullptr);
    }
    if ((output[0][1] & 255u) != capacity || (output[0][1] >> 8) != 3u || output[1][0] != capacity)
        throw std::runtime_error("Packed append lost count or unknown/overflow flags");
    for (UINT pixel = 1; pixel < width * height; ++pixel)
        if (output[0][pixel * 2] != UINT32_MAX || output[0][pixel * 2 + 1] != 0)
            throw std::runtime_error("Packed initialization did not clear an untouched pixel");
    UINT head = output[0][0], count = 0;
    std::array<bool, capacity> visited{};
    while (head != UINT32_MAX) {
        if (head >= capacity || visited[head]) throw std::runtime_error("Packed append produced an invalid list");
        visited[head] = true; ++count;
        const UINT address = head * recordWords;
        if (output[2][address + 1] != 0 || output[2][address + 2] != 0 || output[2][address + 3] != 0 ||
            output[2][address + 4] != bits(0.25f) || output[2][address + 7] != bits(0.5f) ||
            output[2][address + 8] != bits(0.5f) || output[2][address + 9] != bits(1.0f) ||
            output[2][address + 10] != 9 || output[2][address + 23] != bits(0.25f) ||
            output[2][address + 24] != bits(0.75f) || output[2][address + 25] != bits(0.5f) ||
            output[2][address + 28] != bits(0.25f) || output[2][address + 31] != bits(1.0f))
            throw std::runtime_error("Packed capture record layout differs from the consumer");
        head = output[2][address];
    }
    if (count != capacity) throw std::runtime_error("Packed append lost a linked record");
    std::cout << "packed-append-preserves-concurrent-flags count=" << count << " flags=" << (output[0][1] >> 8) << '\n';
    gpu.resources.clear();
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Expected compiled shader directory");
        Gpu gpu(argv[1]);
        const Scenario cases[]{
            {"two-material-current-transport", 0, false, false, true, false, 0.210f},
            {"no-presented-history", 0, false, false, false},
            {"unknown-keeps-current", 2}, {"overflow-keeps-current", 1},
            {"empty-does-not-resurrect", 0, true}, {"owner-change-rejects-history", 0, false, true},
            {"moving-material-finds-old-location", 0, false, false, true, true, 0.210f}
        };
        for (const auto& scenario : cases) runCase(gpu, scenario);
        runAppendCase(gpu);
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n'; return 1;
    }
}
