#include "mcdr_abi.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <numeric>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
namespace fs = std::filesystem;
using Words = std::vector<uint32_t>;
using Work = std::pair<uint32_t, uint32_t>;
constexpr uint32_t canary = 0xa57c39e1u;

std::string lastError() {
    std::array<char, 16384> message{};
    mcdrLastError(message.data(), static_cast<int32_t>(message.size()));
    return message.data();
}

void require(bool success, const std::string& operation) {
    if (!success) throw std::runtime_error(operation + ": " + lastError());
}

std::string readText(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Read " + path.string());
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<uint8_t> compile(const std::string& source, const char* entry,
                           const char* profile, const fs::path& path) {
    const auto size = mcdr_dx12CompileShaderDxc(source.data(), static_cast<int32_t>(source.size()),
        entry, profile, nullptr, 0);
    require(size > 0, "Compile size " + path.string());
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    require(mcdr_dx12CompileShaderDxc(source.data(), static_cast<int32_t>(source.size()), entry,
        profile, bytes.data(), size) == size, "Compile " + path.string());
    std::ofstream output(path, std::ios::binary);
    output.write(reinterpret_cast<const char*>(bytes.data()), size);
    require(output.good(), "Write " + path.string());
    return bytes;
}

struct DeviceRun {
    mcdr_command_ctx* context = nullptr;
    std::vector<mcdr_object*> buffers;
    std::vector<void*> programs;
    std::vector<mcdr_query_pool*> queryPools;
    uint64_t pendingFence = 0;
    uint64_t completedFence = 0;
    uint64_t recordingOrdinal = 0;

    ~DeviceRun() {
        if (pendingFence != 0) return;
        if (context) mcdr_dx12DestroyCommandEncoder(context);
        for (auto* pool : queryPools) mcdr_dx12DestroyQueryPool(pool);
        for (auto* program : programs) mcdr_dx12DestroyWorkGraph(program);
        for (auto* buffer : buffers) mcdr_dx12DestroyResource(buffer);
    }

    mcdr_object* createBuffer(int32_t usage, const Words& words) {
        const auto bytes = static_cast<int64_t>(words.size() * sizeof(uint32_t));
        auto* buffer = mcdr_dx12CreateBuffer(usage, bytes);
        require(buffer != nullptr, "Create buffer");
        buffers.push_back(buffer);
        if ((usage & 2) != 0) {
            int64_t mappedBytes = 0;
            auto* mapped = mcdr_dx12MapBuffer(buffer, 0, bytes, 0, 1, &mappedBytes);
            require(mapped != nullptr && mappedBytes >= bytes, "Map upload");
            std::memcpy(mapped, words.data(), static_cast<size_t>(bytes));
            mcdr_dx12UnmapBuffer(buffer);
        }
        return buffer;
    }

    void* createProgram(const std::vector<uint8_t>& dxil, const char* name, const char* entry,
                        uint32_t recordBytes, const std::vector<uint32_t>& bindings) {
        auto* program = mcdr_dx12CreateWorkGraph(1, dxil.data(), static_cast<int32_t>(dxil.size()),
            name, entry, recordBytes, bindings.data(), static_cast<uint32_t>(bindings.size() / 3),
            512ull * 1024 * 1024);
        require(program != nullptr, std::string("Create graph ") + name);
        programs.push_back(program);
        return program;
    }

    void begin() {
        if (!context) context = MCDR_D3D12_create_command_encoder();
        require(context != nullptr, "Create context");
        mcdr_dx12BeginCommandList(context);
        require(mcdr_dx12IsListOpen(context) == 1, "Begin recording");
        ++recordingOrdinal;
    }

    void submitAndWait() {
        pendingFence = mcdr_dx12Submit(context);
        require(pendingFence != 0, "Submit");
        require(mcdr_dx12WaitForFence(context, pendingFence, 60'000'000'000LL) == 1, "Wait fence");
        completedFence = pendingFence;
        pendingFence = 0;
    }

    Words read(mcdr_object* buffer, size_t words) {
        const auto bytes = static_cast<int64_t>(words * sizeof(uint32_t));
        int64_t mappedBytes = 0;
        auto* mapped = mcdr_dx12MapBuffer(buffer, 0, bytes, 1, 0, &mappedBytes);
        require(mapped != nullptr && mappedBytes >= bytes, "Map results");
        Words result(words);
        std::memcpy(result.data(), mapped, static_cast<size_t>(bytes));
        mcdr_dx12UnmapBuffer(buffer);
        return result;
    }
};

struct Section {
    uint32_t count;
    int32_t x;
    bool sectionBounds;
    std::vector<uint32_t> directions;
    std::vector<bool> expectedCull;
};

struct Case {
    std::string name;
    std::vector<Section> sections;
    uint32_t capacity;
    uint32_t cullEnabled;
};

struct Input {
    Words candidates;
    Words atlas;
    std::set<Work> expected;
};

Input buildInput(const Case& test, uint32_t backfaceCull) {
    Input input;
    uint32_t descriptor = 3;
    input.atlas.resize(descriptor * 4, canary);
    for (uint32_t section = 0; section < test.sections.size(); ++section) {
        const auto& spec = test.sections[section];
        const std::array<uint32_t, 10> candidate{100u + section, 200u + section, descriptor,
            spec.count, static_cast<uint32_t>(spec.x), 0u, 0u, 300u + section, 400u + section,
            spec.sectionBounds ? 1u : 0u};
        input.candidates.insert(input.candidates.end(), candidate.begin(), candidate.end());
        for (uint32_t index = 0; index < spec.count; ++index) {
            const auto direction = spec.directions[index % spec.directions.size()];
            input.atlas.insert(input.atlas.end(), {0u, 0x00ffffffu | (direction << 24u),
                canary, canary});
            const bool alive = test.cullEnabled == 0 ||
                (spec.x == 10 && backfaceCull == 0) ||
                spec.expectedCull[index % spec.expectedCull.size()];
            if (alive) input.expected.emplace(descriptor + index, section);
        }
        descriptor += spec.count;
    }
    return input;
}

std::set<Work> dispatchCase(DeviceRun& gpu, void* program, const Case& test,
                            uint32_t backfaceCull, const std::string& version) {
    const auto input = buildInput(test, backfaceCull);
    Words globals(64, 0u);
    globals[0] = test.capacity;
    globals[1] = test.cullEnabled;
    globals[2] = static_cast<uint32_t>(test.sections.size());
    const auto visibleWords = 8 + test.sections.size() * 8 + test.capacity * 2;
    Words initial(visibleWords + 16, canary);
    std::fill(initial.begin(), initial.begin() + 8, 0u);
    initial[2] = static_cast<uint32_t>(test.sections.size());
    initial[3] = test.capacity;
    auto* constants = gpu.createBuffer(2 | 4, globals);
    auto* candidates = gpu.createBuffer(2 | 4, input.candidates);
    auto* atlas = gpu.createBuffer(2 | 4, input.atlas);
    auto* upload = gpu.createBuffer(2 | 4, initial);
    auto* output = gpu.createBuffer(1024 | 4 | 8, initial);
    auto* readback = gpu.createBuffer(1 | 8, initial);
    const auto outputBytes = static_cast<uint64_t>(initial.size() * 4);
    const std::array<mcdr_object*, 4> bindings{constants, candidates, atlas, output};
    const std::array<uint64_t, 4> offsets{};
    const std::array<uint64_t, 4> lengths{globals.size() * 4, input.candidates.size() * 4,
        input.atlas.size() * 4, outputBytes};
    alignas(16) const std::array<uint32_t, 4> record{
        (static_cast<uint32_t>(test.sections.size()) + 63u) / 64u, 1u, 1u,
        static_cast<uint32_t>(test.sections.size())};
    gpu.begin();
    require(mcdr_dx12CopyBufferChecked(gpu.context, upload, 0, output, 0, outputBytes) == 1,
        "Initialize output");
    require(mcdr_dx12DispatchWorkGraph(gpu.context, program, record.data(), 1, 16,
        bindings.data(), offsets.data(), lengths.data(), 4) == 1, "Dispatch " + test.name);
    require(mcdr_dx12CopyBufferChecked(gpu.context, output, 0, readback, 0, outputBytes) == 1,
        "Copy results");
    gpu.submitAndWait();
    const auto result = gpu.read(readback, initial.size());
    const auto label = version + "/backface=" + std::to_string(backfaceCull) + "/" + test.name;
    require(result[0] == input.expected.size(), label + " actual count");
    require(result[1] == (input.expected.size() > test.capacity ? 1u : 0u), label + " overflow");
    require(std::equal(result.begin() + 2, result.begin() + 8, initial.begin() + 2), label + " header");
    for (size_t section = 0; section < test.sections.size(); ++section) {
        require(std::equal(result.begin() + 8 + section * 8, result.begin() + 16 + section * 8,
            input.candidates.begin() + section * 10), label + " section table=" + std::to_string(section));
    }
    std::set<Work> actual;
    const auto written = std::min<size_t>(input.expected.size(), test.capacity);
    const auto workBase = 8 + test.sections.size() * 8;
    for (size_t index = 0; index < written; ++index) {
        const Work work{result[workBase + index * 2], result[workBase + index * 2 + 1]};
        require(input.expected.contains(work), label + " unexpected work");
        require(actual.insert(work).second, label + " duplicate work");
    }
    if (input.expected.size() <= test.capacity) require(actual == input.expected, label + " visible set");
    for (size_t index = workBase + written * 2; index < result.size(); ++index)
        require(result[index] == canary, label + " output canary word=" + std::to_string(index));
    std::cout << "PASS " << label << " count=" << result[0] << " overflow=" << result[1]
        << " written=" << written << " sectionTable=exact unique=1 canary=unchanged\n";
    return actual;
}

std::string shaderPrefix(uint32_t backfaceCull) {
    return "#define TERRAIN_CAMERA_BLOCK int3(0,0,0)\n"
        "#define TERRAIN_CAMERA_OFFSET float3(0,0,0)\n"
        "#define TERRAIN_MODEL_VIEW float4x4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1)\n"
        "#define TERRAIN_PROJECTION float4x4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1)\n"
        "#define TERRAIN_BACKFACE_CULL " + std::to_string(backfaceCull) + "\n";
}

enum class TimingSequence { SingleRecording, PairSubmission };

void timeGraphs(DeviceRun& gpu, const std::array<void*, 2>& programs, const fs::path& directory,
                TimingSequence sequence) {
    constexpr uint32_t samples = 16;
    const auto frequency = MCDR_D3D12_get_timestamp_frequency();
    require(frequency > 0, "Timestamp frequency");
    std::cout << "Timestamp frequency=" << frequency << " Hz; GPU duration includes graph dispatch and "
        "its UAV barrier, excludes initialize copy/readback; alternating old/new order; 1 warmup each.\n";
    std::vector<uint32_t> sparseDirections(64, 1u);
    std::vector<bool> sparseExpected(64, false);
    sparseDirections[63] = 2u;
    sparseExpected[63] = true;
    std::vector<Case> workloads{
        {"dense-16x4096", std::vector<Section>(16, {4096, 10, false, {2}, {true}}), 65536, 1},
        {"sparse-16x4096-one-per64", std::vector<Section>(16,
            {4096, 10, false, sparseDirections, sparseExpected}), 65536, 1},
        {"tails-1024x1", std::vector<Section>(1024, {1, 10, false, {2}, {true}}), 1024, 1}
    };
    if (sequence == TimingSequence::PairSubmission) workloads.pop_back();
    const auto sequenceName = sequence == TimingSequence::PairSubmission ? "pair-submission" : "single-recording";
    std::cout << "TIMING sequence=" << sequenceName << '\n';
    std::ofstream csv(directory / (sequence == TimingSequence::PairSubmission ? "timings-pairs.csv" : "timings.csv"));
    std::ofstream lifecycle(directory / (std::string(sequenceName) + "-lifecycle.csv"));
    lifecycle << "workload,round,recording_ordinal,first_version,old_query,new_query,completed_fence\n";
    csv << "workload,round,version,gpu_ticks,gpu_us\n";
    for (const auto& test : workloads) {
        const auto input = buildInput(test, 1);
        Words globals(64, 0u);
        globals[0] = test.capacity;
        globals[1] = test.cullEnabled;
        globals[2] = static_cast<uint32_t>(test.sections.size());
        Words initial(8 + test.sections.size() * 8 + test.capacity * 2 + 16, canary);
        std::fill(initial.begin(), initial.begin() + 8, 0u);
        initial[2] = static_cast<uint32_t>(test.sections.size());
        initial[3] = test.capacity;
        auto* constants = gpu.createBuffer(2 | 4, globals);
        auto* candidates = gpu.createBuffer(2 | 4, input.candidates);
        auto* atlas = gpu.createBuffer(2 | 4, input.atlas);
        auto* upload = gpu.createBuffer(2 | 4, initial);
        auto* output = gpu.createBuffer(1024 | 4 | 8, initial);
        const auto bytes = static_cast<uint64_t>(initial.size() * 4);
        const std::array<mcdr_object*, 4> bindings{constants, candidates, atlas, output};
        const std::array<uint64_t, 4> offsets{};
        const std::array<uint64_t, 4> lengths{globals.size() * 4, input.candidates.size() * 4,
            input.atlas.size() * 4, bytes};
        alignas(16) const std::array<uint32_t, 4> record{
            (static_cast<uint32_t>(test.sections.size()) + 63u) / 64u, 1u, 1u,
            static_cast<uint32_t>(test.sections.size())};
        auto* pool = mcdr_dx12CreateQueryPool(samples * 4);
        require(pool != nullptr, "Create timestamp query pool");
        gpu.queryPools.push_back(pool);
        for (const auto* program : programs) {
            gpu.begin();
            require(mcdr_dx12CopyBufferChecked(gpu.context, upload, 0, output, 0, bytes) == 1, "Warmup initialize");
            require(mcdr_dx12DispatchWorkGraph(gpu.context, const_cast<void*>(program), record.data(), 1, 16,
                bindings.data(), offsets.data(), lengths.data(), 4) == 1, "Warmup dispatch");
            gpu.submitAndWait();
        }
        if (sequence == TimingSequence::SingleRecording) gpu.begin();
        for (uint32_t round = 0; round < samples; ++round) {
            if (sequence == TimingSequence::PairSubmission) gpu.begin();
            for (uint32_t position = 0; position < 2; ++position) {
                const auto version = (round + position) % 2;
                const auto query = static_cast<int32_t>((round * 2 + version) * 2);
                require(mcdr_dx12CopyBufferChecked(gpu.context, upload, 0, output, 0, bytes) == 1, "Timed initialize");
                mcdr_dx12WriteTimestamp(gpu.context, pool, query);
                require(mcdr_dx12DispatchWorkGraph(gpu.context, programs[version], record.data(), 1, 16,
                    bindings.data(), offsets.data(), lengths.data(), 4) == 1, "Timed dispatch");
                mcdr_dx12WriteTimestamp(gpu.context, pool, query + 1);
            }
            if (sequence == TimingSequence::PairSubmission) {
                gpu.submitAndWait();
                lifecycle << test.name << ',' << round << ',' << gpu.recordingOrdinal << ','
                    << (round % 2 == 0 ? "old" : "new") << ',' << round * 4 << ',' << round * 4 + 2
                    << ',' << gpu.completedFence << '\n';
            }
        }
        if (sequence == TimingSequence::SingleRecording) gpu.submitAndWait();
        std::array<int64_t, samples * 4> timestamps{};
        require(mcdr_dx12ReadQueryValues(pool, 0, static_cast<int32_t>(timestamps.size()), timestamps.data()) == 1,
            "Read timestamp values");
        std::array<std::vector<double>, 2> durations;
        for (uint32_t round = 0; round < samples; ++round) {
            for (uint32_t version = 0; version < 2; ++version) {
                const auto query = (round * 2 + version) * 2;
                const auto ticks = timestamps[query + 1] - timestamps[query];
                require(ticks > 0, "Positive GPU time");
                const auto microseconds = static_cast<double>(ticks) * 1e6 / static_cast<double>(frequency);
                durations[version].push_back(microseconds);
                csv << test.name << ',' << round << ',' << (version == 0 ? "old" : "new") << ','
                    << ticks << ',' << std::fixed << std::setprecision(6) << microseconds << '\n';
            }
        }
        std::array<double, 2> medians{};
        for (size_t version = 0; version < 2; ++version) {
            std::sort(durations[version].begin(), durations[version].end());
            medians[version] = (durations[version][samples / 2 - 1] + durations[version][samples / 2]) * .5;
            std::cout << "TIMING " << test.name << ' ' << (version == 0 ? "old" : "new")
                << " median_us=" << medians[version] << " min_us=" << durations[version].front()
                << " max_us=" << durations[version].back()
                << " mean_us=" << std::accumulate(durations[version].begin(), durations[version].end(), 0.0) / samples
                << " samples=" << samples << '\n';
        }
        std::cout << "TIMING " << test.name << " new/old=" << medians[1] / medians[0] << '\n';
    }
}

void measureWaveWidth(DeviceRun& gpu, const fs::path& directory) {
    const std::string source = R"(
RWByteAddressBuffer result : register(u0);
struct Record { uint unused; };
[Shader("node")][NodeLaunch("broadcasting")][NodeIsProgramEntry]
[NodeDispatchGrid(1,1,1)][NumThreads(64,1,1)]
void Entry(DispatchNodeInputRecord<Record> input, uint thread : SV_GroupIndex) {
    result.Store(thread * 4u, WaveGetLaneCount());
})";
    const auto dxil = compile(source, "", "lib_6_9", directory / "wave-width.dxil");
    auto* program = gpu.createProgram(dxil, "WaveWidth", "Entry", 4, {2, 0, 0});
    const Words empty(64, 0);
    auto* output = gpu.createBuffer(1024 | 4 | 8, empty);
    auto* readback = gpu.createBuffer(1 | 8, empty);
    const std::array<mcdr_object*, 1> bindings{output};
    const std::array<uint64_t, 1> offsets{0}, lengths{256};
    const uint32_t record = 0;
    gpu.begin();
    require(mcdr_dx12DispatchWorkGraph(gpu.context, program, &record, 1, 4,
        bindings.data(), offsets.data(), lengths.data(), 1) == 1, "Measure wave width");
    require(mcdr_dx12CopyBufferChecked(gpu.context, output, 0, readback, 0, 256) == 1, "Copy wave width");
    gpu.submitAndWait();
    const auto widths = gpu.read(readback, 64);
    const std::set<uint32_t> unique(widths.begin(), widths.end());
    std::cout << "Actual runtime wave lane counts:";
    for (const auto width : unique) std::cout << ' ' << width;
    std::cout << '\n';
}
}

int main(int argumentCount, char** arguments) {
    try {
        require(argumentCount == 5 || argumentCount == 6,
            "Usage: terrain-nodes.exe new.hlsl old.hlsl arguments.hlsl report-directory [--pairs-only]");
        const bool pairsOnly = argumentCount == 6 && std::string(arguments[5]) == "--pairs-only";
        require(argumentCount == 5 || pairsOnly, "Unknown mode");
        std::array<char, 16384> description{};
        require(mcdr_dx12CreateDevice(description.data(), static_cast<int32_t>(description.size())) > 0,
            "Create device");
        std::cout << description.data() << '\n';
        require(mcdr_dx12GetAdapterInfo(description.data(), static_cast<int32_t>(description.size())) > 0,
            "Query adapter");
        std::cout << description.data() << '\n';
        {
            DeviceRun gpu;
            const fs::path directory(arguments[4]);
            if (!pairsOnly) measureWaveWidth(gpu, directory);
            const std::array<std::string, 2> versions{"old", "new"};
            const std::array<std::string, 2> sources{readText(arguments[2]), readText(arguments[1])};
            const Section mixed{129, 10, false, {1, 2, 64, 128, 0}, {false, true, true, true, false}};
            std::vector<Case> cases;
            for (const auto count : {0u, 1u, 63u, 64u, 65u, 129u}) {
                cases.push_back({"visible-tail-" + std::to_string(count),
                    {{count, 10, false, {2}, {true}}}, std::max(count, 1u), 1});
                cases.push_back({"culled-tail-" + std::to_string(count),
                    {{count, 10, false, {1}, {false}}}, std::max(count, 1u), 1});
            }
            cases.push_back({"mixed-directions", {mixed}, 129, 1});
            cases.push_back({"runtime-cull-disabled", {mixed}, 129, 0});
            cases.push_back({"far-meshlet-bounds", {{65, 1000, false, {2, 64, 128}, {false, false, true}}}, 65, 1});
            cases.push_back({"far-section-bounds", {{65, 1000, true, {128}, {false}}}, 65, 1});
            cases.push_back({"multi-section", {{0, 0, false, {2}, {true}}, mixed,
                {63, 10, false, {2}, {true}}, {65, 10, false, {1}, {false}},
                {1, 1000, false, {128}, {true}}}, 400, 1});
            cases.push_back({"capacity-exact", {{65, 10, false, {2}, {true}},
                {64, 10, false, {2}, {true}}}, 129, 1});
            cases.push_back({"capacity-overflow", {{65, 10, false, {2}, {true}},
                {64, 10, false, {2}, {true}}}, 64, 1});
            cases.push_back({"capacity-one", {mixed}, 1, 1});
            std::array<std::vector<std::set<Work>>, 2> results;
            std::array<void*, 2> timedPrograms{};
            for (uint32_t backface = pairsOnly ? 1u : 0u; backface <= 1; ++backface) {
                for (size_t version = 0; version < versions.size(); ++version) {
                    const auto source = shaderPrefix(backface) + sources[version];
                    const auto stem = versions[version] + "-backface-" + std::to_string(backface);
                    std::ofstream(directory / (stem + ".hlsl"), std::ios::binary) << source;
                    const auto dxil = compile(source, "", "lib_6_9", directory / (stem + ".dxil"));
                    auto* program = gpu.createProgram(dxil, "Terrain", "SectionEntry", 16,
                        {0, 8, 0, 1, 15, 0, 1, 16, 0, 2, 0, 0});
                    if (backface == 1) timedPrograms[version] = program;
                    results[version].clear();
                    if (!pairsOnly) {
                        for (const auto& test : cases)
                            results[version].push_back(dispatchCase(gpu, program, test, backface, versions[version]));
                    }
                }
                if (pairsOnly) continue;
                for (size_t index = 0; index < cases.size(); ++index) {
                    const auto expected = buildInput(cases[index], backface).expected.size();
                    if (expected <= cases[index].capacity)
                        require(results[0][index] == results[1][index], "Old/new set " + cases[index].name);
                }
            }
            if (!pairsOnly) for (const auto width : {32u, 64u}) {
                auto source = shaderPrefix(1) + sources[1];
                const auto target = source.find("void MeshletRange(");
                require(target != std::string::npos, "Find MeshletRange for offline WaveSize attribute");
                source.insert(target, "[WaveSize(" + std::to_string(width) + ")]\n");
                const auto stem = "new-offline-wave" + std::to_string(width);
                std::ofstream(directory / (stem + ".hlsl"), std::ios::binary) << source;
                compile(source, "", "lib_6_9", directory / (stem + ".dxil"));
                std::cout << "PASS offline WaveSize(" << width << ") compile; not dispatched.\n";
            }
            if (!pairsOnly) {
                const auto argumentSource = readText(arguments[3]);
                compile(argumentSource, "InitializeTerrain", "cs_6_9", directory / "initialize.dxil");
                compile(argumentSource, "FinalizeTerrainArguments", "cs_6_9", directory / "finalize.dxil");
                std::cout << "PASS production argument compute entries compile. Finalize GPU execution NOT COVERED: "
                    "generic compute ABI exposes texture UAV u0/b0 only; terrain owns its private output buffers.\n";
                std::cout << "PASS " << cases.size() * 4 << " production graph dispatches; old/new complete sets equal; "
                    "overflow subsets individually valid, unique and bounded.\n";
            }
            timeGraphs(gpu, timedPrograms, directory,
                pairsOnly ? TimingSequence::PairSubmission : TimingSequence::SingleRecording);
        }
        require(mcdr_dx12DestroyDeviceChecked() == 1, "Destroy device");
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        std::cerr << "Abort cleanup: process exit retains resources if a submitted fence was not completed.\n";
        return 1;
    }
}
