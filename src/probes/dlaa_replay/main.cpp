#define NOMINMAX
#include <windows.h>
#include "mcdr_abi.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using Bytes = std::vector<uint8_t>;

std::string lastError() {
    char text[4096]{};
    mcdrLastError(text, sizeof(text));
    return text;
}
void requireResult(int result, const std::string& operation) {
    if (result != 1) throw std::runtime_error(operation + " result=" + std::to_string(result) + " error=" + lastError());
}
template<class Action> void invokeChecked(const std::string& operation, Action action) {
    auto before = lastError();
    action();
    auto after = lastError();
    if (after != before && !after.empty()) throw std::runtime_error(operation + ": " + after);
}
Bytes readBytes(const fs::path& file, size_t expected) {
    std::ifstream input(file, std::ios::binary | std::ios::ate);
    if (!input || input.tellg() != static_cast<std::streamoff>(expected)) throw std::runtime_error("Unexpected input size: " + file.string());
    Bytes result(expected);
    input.seekg(0);
    input.read(reinterpret_cast<char*>(result.data()), result.size());
    if (!input) throw std::runtime_error("Read failed: " + file.string());
    return result;
}
void saveBytes(const fs::path& file, const Bytes& bytes) {
    std::ofstream output(file, std::ios::binary);
    output.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    if (!output) throw std::runtime_error("Write failed: " + file.string());
}
std::map<std::string, std::string> readProperties(const fs::path& file) {
    std::ifstream input(file);
    if (!input) throw std::runtime_error("Missing metadata: " + file.string());
    std::map<std::string, std::string> properties;
    std::string line;
    while (std::getline(input, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        auto separator = line.find('=');
        if (separator == std::string::npos || line.front() == '#') continue;
        auto value = line.substr(separator + 1);
        for (size_t index = 0; index + 1 < value.size(); ++index)
            if (value[index] == '\\' && (value[index + 1] == '=' || value[index + 1] == ':' || value[index + 1] == '\\')) value.erase(index, 1);
        if (!properties.emplace(line.substr(0, separator), value).second) throw std::runtime_error("Duplicate metadata key");
    }
    return properties;
}
struct Capture {
    std::map<std::string, std::string> properties;
    int width, height;
    size_t imageBytes;
    Bytes color, depth, motion, output, bias;
    explicit Capture(const fs::path& directory) : properties(readProperties(directory / "metadata.properties")),
        width(std::stoi(properties.at("DisplayWidth"))), height(std::stoi(properties.at("DisplayHeight"))),
        imageBytes(width > 0 && height > 0 && width <= 16384 && height <= 16384 ? size_t(width) * height * 4 : throw std::runtime_error("Invalid extent")),
        color(readBytes(directory / "color.bin", imageBytes)),
        depth(readBytes(directory / "depth.bin", imageBytes)), motion(readBytes(directory / "motion.bin", imageBytes)),
        output(readBytes(directory / "reconstructionOutput.bin", imageBytes)) {
        if (fs::exists(directory / "bias.bin")) bias = readBytes(directory / "bias.bin", size_t(width) * height);
        for (const auto& role : {"color", "depth", "motion", "reconstructionOutput"}) {
            if (std::stoi(properties.at(std::string(role) + ".width")) != width ||
                std::stoi(properties.at(std::string(role) + ".height")) != height ||
                std::stoi(properties.at(std::string(role) + ".rowPitch")) != width * 4 ||
                properties.at(std::string(role) + ".recorded") != "true") throw std::runtime_error("Capture texture layout mismatch");
        }
        if (properties.at("color.format") != "RGBA8_UNORM" || properties.at("depth.format") != "D32_FLOAT" ||
            properties.at("motion.format") != "RG16_FLOAT" || properties.at("reconstructionOutput.format") != "RGBA8_UNORM")
            throw std::runtime_error("Unsupported capture format");
        if (properties.contains("error") && properties.at("error") != "none") throw std::runtime_error("Capture contains an error: " + properties.at("error"));
    }
    uint64_t integer(const std::string& key) const {
        const auto& value = properties.at(key);
        size_t consumed{};
        if (value.empty() || value.front() == '-') throw std::runtime_error("Invalid unsigned metadata: " + key);
        auto result = std::stoull(value, &consumed);
        if (consumed != value.size()) throw std::runtime_error("Invalid integer metadata: " + key);
        return result;
    }
    std::string setting(const std::string& key) const {
        const auto& settings = properties.at("settings");
        auto start = settings.find(key + "=");
        if (start == std::string::npos) throw std::runtime_error("Missing captured setting: " + key);
        start += key.size() + 1;
        auto end = settings.find_first_of(",)", start);
        if (end == std::string::npos) throw std::runtime_error("Invalid captured settings");
        return settings.substr(start, end - start);
    }
    mcdr_raster_camera camera(const std::string& prefix) const {
        mcdr_raster_camera result{};
        result.origin[0] = std::stod(properties.at(prefix + ".OriginX"));
        result.origin[1] = std::stod(properties.at(prefix + ".OriginY"));
        result.origin[2] = std::stod(properties.at(prefix + ".OriginZ"));
        result.jitterX = std::stod(properties.at(prefix + ".JitterX"));
        result.jitterY = std::stod(properties.at(prefix + ".JitterY"));
        result.yaw = std::stof(properties.at(prefix + ".Yaw"));
        result.pitch = std::stof(properties.at(prefix + ".Pitch"));
        for (const auto& entry : {std::pair{"projection", result.projection}, std::pair{"rasterProjection", result.rasterProjection}, std::pair{"viewRotation", result.viewRotation}}) {
            auto values = properties.at(prefix + "." + entry.first);
            for (auto& character : values) if (character == '[' || character == ']' || character == ',') character = ' ';
            std::istringstream numbers(values);
            for (int index = 0; index < 16; ++index)
                if (!(numbers >> entry.second[index]) || !std::isfinite(entry.second[index])) throw std::runtime_error("Invalid camera matrix");
        }
        if (properties.contains(prefix + ".Perspective.available")) {
            if (properties.at(prefix + ".Perspective.available") != "true") throw std::runtime_error("Captured perspective is unavailable");
            result.nearPlane = std::stof(properties.at(prefix + ".Perspective.NearPlane"));
            result.farPlane = std::stof(properties.at(prefix + ".Perspective.FarPlane"));
            result.verticalFovRadians = std::stof(properties.at(prefix + ".Perspective.VerticalFovRadians"));
        } else {
            result.nearPlane = result.projection[14] / (1.0f + result.projection[10]);
            result.farPlane = result.projection[14] / result.projection[10];
            result.verticalFovRadians = 2.0f * std::atan(1.0f / result.projection[5]);
        }
        if (!std::isfinite(result.nearPlane) || !std::isfinite(result.farPlane) || !std::isfinite(result.verticalFovRadians) ||
            result.nearPlane <= 0 || result.farPlane <= result.nearPlane || result.verticalFovRadians <= 0 || result.verticalFovRadians >= 3.141592654f)
            throw std::runtime_error("Invalid captured or derived perspective");
        result.flags = 257;
        if (properties.at(prefix + ".Panoramic") != "false") throw std::runtime_error("Unsupported panoramic capture");
        return result;
    }
    mcdr_vanilla_frame_input frameInputs() const {
        if (setting("antiAliasing") != "3" || setting("reconstructionBackend") != "1" || setting("renderScalePercent") != "100" ||
            setting("jitterEnabled") != "true" || setting("jitterSamples") != "16" || setting("dlssQuality") != "4" ||
            (setting("dlssPreset") != "0" && setting("dlssPreset") != "11") || setting("frameGenerationBackend") != "0" || setting("frameGenerationMode") != "0")
            throw std::runtime_error("Capture requires DLAA K/default, 16-phase jitter, FG off");
        if (properties.at("Current.available") != "true" || properties.at("Previous.available") != "true")
            throw std::runtime_error("Capture must contain Current and Previous cameras");
        mcdr_vanilla_frame_input frame{};
        frame.schemaVersion = MCDR_VANILLA_FRAME_SCHEMA_VERSION;
        frame.structSize = sizeof(frame);
        frame.identity = {integer("identity.WorldEpoch"), integer("identity.RealFrameId"), integer("identity.ConfigVersion"), integer("identity.ResourceVersion")};
        if (!frame.identity.worldEpoch || !frame.identity.realFrameId || !frame.identity.configVersion || !frame.identity.resourceVersion) throw std::runtime_error("Zero frame identity");
        frame.historyGeneration = integer("HistoryGeneration");
        frame.frameTimeNanos = integer("FrameTimeNanos");
        frame.jitterPhaseIndex = integer("JitterPhaseIndex");
        frame.renderWidth = frame.displayWidth = width;
        frame.renderHeight = frame.displayHeight = height;
        frame.flags = frame.colorEncoding = frame.depthConvention = frame.motionConvention = 1;
        frame.frameDeltaMs = std::stof(properties.at("FrameDeltaMs"));
        if (!std::isfinite(frame.frameDeltaMs) || frame.frameDeltaMs <= 0) throw std::runtime_error("Invalid FrameDeltaMs");
        frame.preExposure = frame.motionScaleX = frame.motionScaleY = frame.viewSpaceToMeters = 1;
        frame.previousRealFrameId = frame.identity.realFrameId - 1;
        frame.settings.antiAliasing = 3;
        frame.settings.reconstructionBackend = 1;
        frame.settings.renderScalePercent = 100;
        frame.settings.jitterEnabled = 1;
        frame.settings.jitterSamples = 16;
        frame.settings.dlssQuality = 4;
        frame.settings.dlssPreset = 11;
        frame.settings.frameGenerationFrames = 1;
        frame.settings.taaBlend = std::stof(setting("taaBlend"));
        frame.settings.taaSharpness = std::stof(setting("taaSharpness"));
        frame.current = camera("Current");
        frame.previous = camera("Previous");
        return frame;
    }
};
int difference(const Bytes& first, const Bytes& second, size_t pixel) {
    int maximum = 0;
    for (int channel = 0; channel < 3; ++channel) maximum = std::max(maximum, std::abs(int(first[pixel * 4 + channel]) - int(second[pixel * 4 + channel])));
    return maximum;
}
std::vector<size_t> candidatePoints(const Capture& first, const Capture& second) {
    const int width = first.width, height = first.height;
    if (second.bias.empty()) throw std::runtime_error("Two-frame legacy candidate selection requires bias.bin; use --sequence for ABI13 captures");
    std::vector<size_t> points;
    for (size_t pixel = 0; pixel < width * height; ++pixel) {
        if (second.bias[pixel] != 255 || difference(first.color, second.color, pixel) < 32 || difference(first.output, second.output, pixel) > 3 ||
            difference(second.color, second.output, pixel) < 32 || difference(second.output, first.color, pixel) > 12) continue;
        int outside = 0;
        int centerX = int(pixel % width), centerY = int(pixel / width);
        for (int channel = 0; channel < 3; ++channel) {
            int low = 255, high = 0;
            for (int row = std::max(0, centerY - 2); row <= std::min(height - 1, centerY + 2); ++row)
                for (int column = std::max(0, centerX - 2); column <= std::min(width - 1, centerX + 2); ++column) {
                    int value = second.color[(row * width + column) * 4 + channel];
                    low = std::min(low, value);
                    high = std::max(high, value);
                }
            outside = std::max({outside, low - int(second.output[pixel * 4 + channel]), int(second.output[pixel * 4 + channel]) - high});
        }
        if (outside > 16) points.push_back(pixel);
    }
    return points;
}
enum class InputAlpha { Captured, Opaque };
enum class ReplayColor { GammaUnorm8, LinearFloat32, PreparedGammaUnorm8 };

Bytes srgbBytesToLinearFloats(const Bytes& encoded) {
    std::array<float, 256> transfer{};
    for (size_t index = 0; index < transfer.size(); ++index) {
        const double value = double(index) / 255.0;
        transfer[index] = static_cast<float>(value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4));
    }
    Bytes linear(encoded.size() * sizeof(float));
    for (size_t index = 0; index < encoded.size(); ++index) {
        const float value = index % 4 == 3 ? float(encoded[index]) / 255.0f : transfer[encoded[index]];
        std::memcpy(linear.data() + index * sizeof(float), &value, sizeof(value));
    }
    return linear;
}

Bytes linearFloatsToSrgbBytes(const Bytes& linear) {
    if (linear.size() % (4 * sizeof(float))) throw std::runtime_error("Invalid linear RGBA32_FLOAT size");
    Bytes encoded(linear.size() / sizeof(float));
    for (size_t index = 0; index < encoded.size(); ++index) {
        float value{};
        std::memcpy(&value, linear.data() + index * sizeof(float), sizeof(value));
        if (!std::isfinite(value)) throw std::runtime_error("Non-finite reconstructed linear color");
        const double normalized = std::clamp(double(value), 0.0, 1.0);
        const double transferred = index % 4 == 3 ? normalized :
            normalized <= 0.0031308 ? normalized * 12.92 : 1.055 * std::pow(normalized, 1.0 / 2.4) - 0.055;
        encoded[index] = static_cast<uint8_t>(std::lround(transferred * 255.0));
    }
    return encoded;
}

struct Replay {
    const int width, height;
    const size_t imageBytes, colorTransferBytes;
    const InputAlpha inputAlpha;
    const ReplayColor replayColor;
    const int32_t preset;
    Replay(int imageWidth, int imageHeight, InputAlpha alpha, ReplayColor encoding, int32_t selectedPreset) : width(imageWidth), height(imageHeight),
        imageBytes(size_t(width) * height * 4), colorTransferBytes(imageBytes * (encoding == ReplayColor::LinearFloat32 ? sizeof(float) : 1)),
        inputAlpha(alpha), replayColor(encoding), preset(selectedPreset) {}
    mcdr_command_ctx* context{};
    HWND window{};
    mcdr_surface* surface{};
    std::vector<mcdr_object*> resources;
    struct TextureTransfer { mcdr_object *texture, *upload, *readback; size_t size; };
    std::vector<TextureTransfer> transfers;
    mcdr_object *color{}, *depth{}, *motion{}, *exposure{}, *output{}, *scene{}, *outputRead{}, *sceneRead{}, *exposureRead{};
    uint64_t lastFence{}, fenceWaits{}, presentCalls{}, consumedPresents{};
    float lastExposure{};
    Bytes lastLinearOutput;
    mcdr_object* createTexture(int usage, int format, int textureWidth, int textureHeight) {
        auto* object = mcdr_dx12CreateTexture(usage, format, textureWidth, textureHeight, 1, 1);
        if (!object) throw std::runtime_error("CreateTexture: " + lastError());
        resources.push_back(object);
        return object;
    }
    mcdr_object* createBuffer(int usage, size_t size) {
        auto* object = mcdr_dx12CreateBuffer(usage, size);
        if (!object) throw std::runtime_error("CreateBuffer: " + lastError());
        resources.push_back(object);
        return object;
    }
    void create() {
        WNDCLASSEXW windowClass{};
        windowClass.cbSize = sizeof(windowClass);
        windowClass.lpfnWndProc = DefWindowProcW;
        windowClass.hInstance = GetModuleHandleW(nullptr);
        windowClass.lpszClassName = L"IllusionDlaaReplayHidden";
        if (!RegisterClassExW(&windowClass)) throw std::runtime_error("RegisterClassEx failed");
        window = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, windowClass.lpszClassName,
            L"DLAA replay", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, width, height,
            nullptr, nullptr, windowClass.hInstance, nullptr);
        if (!window || IsWindowVisible(window)) throw std::runtime_error("Hidden window creation failed");
        mcdr_dx12SetHdrMode(2);
        mcdr_dx12SetSwapchainOptions(0, 0);
        surface = mcdr_dx12CreateSurface(reinterpret_cast<uint64_t>(window));
        if (!surface) throw std::runtime_error("CreateSurface: " + lastError());
        requireResult(mcdr_dx12ConfigureSurface(surface, width, height, 0), "ConfigureSurface");
        if (IsWindowVisible(window)) throw std::runtime_error("Surface unexpectedly revealed hidden window");
        context = MCDR_D3D12_create_command_encoder();
        if (!context) throw std::runtime_error("CreateCommandEncoder: " + lastError());
        const int colorFormat = replayColor == ReplayColor::LinearFloat32 ? 47 : 6;
        color = createTexture(15, colorFormat, width, height);
        depth = createTexture(15, 51, width, height);
        motion = createTexture(15, 41, width, height);
        exposure = createTexture(33, 44, 1, 1);
        output = createTexture(47, colorFormat, width, height);
        scene = createTexture(47, colorFormat, width, height);
        outputRead = createBuffer(1, colorTransferBytes);
        sceneRead = createBuffer(1, colorTransferBytes);
        exposureRead = createBuffer(1, 4);
    }
    void submitAndWait() {
        lastFence = mcdr_dx12Submit(context);
        if (!lastFence) throw std::runtime_error("Submit: " + lastError());
        requireResult(mcdr_dx12WaitForFence(context, lastFence, 60000000000LL), "WaitForFence=" + std::to_string(lastFence));
        ++fenceWaits;
    }
    Bytes readBuffer(mcdr_object* buffer, size_t size) {
        int64_t mappedLength{};
        auto* mapped = mcdr_dx12MapBuffer(buffer, 0, size, 1, 0, &mappedLength);
        if (!mapped || mappedLength < static_cast<int64_t>(size)) throw std::runtime_error("Read map: " + lastError());
        Bytes bytes(size);
        std::memcpy(bytes.data(), mapped, size);
        mcdr_dx12UnmapBuffer(buffer);
        return bytes;
    }
    void uploadAndVerify(mcdr_object* texture, const Bytes& bytes, int textureWidth, int textureHeight) {
        auto transfer = std::find_if(transfers.begin(), transfers.end(), [texture](const auto& value) { return value.texture == texture; });
        if (transfer == transfers.end()) {
            transfers.push_back({texture, createBuffer(2, bytes.size()), createBuffer(1, bytes.size()), bytes.size()});
            transfer = std::prev(transfers.end());
        }
        if (transfer->size != bytes.size()) throw std::runtime_error("Texture upload size changed");
        auto* upload = transfer->upload;
        auto* readback = transfer->readback;
        int64_t mappedLength{};
        auto* mapped = mcdr_dx12MapBuffer(upload, 0, bytes.size(), 0, 1, &mappedLength);
        if (!mapped || mappedLength < static_cast<int64_t>(bytes.size())) throw std::runtime_error("Upload map: " + lastError());
        std::memcpy(mapped, bytes.data(), bytes.size());
        mcdr_dx12UnmapBuffer(upload);
        invokeChecked("Begin upload", [&] { mcdr_dx12BeginCommandList(context); });
        invokeChecked("Upload texture", [&] { mcdr_dx12CopyBufferToTexture(context, upload, 0, textureWidth, textureHeight, texture, 0, 0, 0, 0, textureWidth, textureHeight); });
        invokeChecked("Read uploaded texture", [&] { mcdr_dx12CopyTextureToBuffer(context, texture, 0, 0, 0, 0, textureWidth, textureHeight, readback, 0); });
        submitAndWait();
        if (readBuffer(readback, bytes.size()) != bytes) throw std::runtime_error("Upload roundtrip mismatch");
    }
    void uploadCapture(const Capture& capture) {
        Bytes opaqueColor;
        Bytes linearColor;
        const Bytes* submittedColor = &capture.color;
        size_t changedAlphaPixels = 0;
        if (inputAlpha == InputAlpha::Opaque) {
            opaqueColor = capture.color;
            for (size_t alphaOffset = 3; alphaOffset < opaqueColor.size(); alphaOffset += 4) {
                changedAlphaPixels += opaqueColor[alphaOffset] != 255;
                opaqueColor[alphaOffset] = 255;
            }
            submittedColor = &opaqueColor;
        }
        if (replayColor == ReplayColor::LinearFloat32) {
            linearColor = srgbBytesToLinearFloats(*submittedColor);
            submittedColor = &linearColor;
        }
        uploadAndVerify(color, *submittedColor, width, height);
        uploadAndVerify(depth, capture.depth, width, height);
        uploadAndVerify(motion, capture.motion, width, height);
        std::cout << "upload byte-identical submitted color/depth/motion fence=" << lastFence
            << " inputAlpha=" << (inputAlpha == InputAlpha::Opaque ? "forced-255" : "captured")
            << " changedAlphaPixels=" << changedAlphaPixels
            << " colorFormat=" << (replayColor == ReplayColor::LinearFloat32 ? "RGBA32_FLOAT-sRGB-EOTF" : "RGBA8_UNORM-gamma") << std::endl;
    }
    void prepare(const Capture& capture) {
        Bytes unitExposure(4);
        float unit = 1.0f;
        std::memcpy(unitExposure.data(), &unit, 4);
        uploadAndVerify(exposure, unitExposure, 1, 1);
        auto camera = capture.camera("Current");
        mcdr_sr_dispatch description{};
        description.renderWidth = description.displayWidth = width;
        description.renderHeight = description.displayHeight = height;
        description.jitterOffsetX = float(camera.jitterX);
        description.jitterOffsetY = float(camera.jitterY);
        description.motionVectorScaleX = description.motionVectorScaleY = description.preExposure = 1;
        description.frameTimeDeltaMs = std::stof(capture.properties.at("FrameDeltaMs"));
        description.cameraNear = camera.nearPlane;
        description.cameraFar = camera.farPlane;
        description.cameraVerticalFov = camera.verticalFovRadians;
        description.cameraAspectRatio = float(width) / height;
        description.viewSpaceToMetersFactor = 1;
        description.reset = description.backend = 1;
        description.pColorInput = color;
        description.pColorOutput = output;
        description.pDepth = depth;
        description.pMotionVectors = motion;
        description.pExposure = exposure;
        description.hdrInput = replayColor == ReplayColor::LinearFloat32 ? 1 : 0;
        description.depthFormatOrdinal = 51;
        description.pCamera = &camera;
        uint64_t token{};
        mcdr_dx12ReflexFrameBegin();
        requireResult(mcdr_dx12SrPrepare(context, &description, 4, preset, &token), "SrPrepare DLAA preset=" + std::to_string(preset));
        requireResult(mcdr_dx12SrCommit(token), "SrCommit");
        std::cout << "prepared token=" << token << " preset=" << preset << " lensSource=" << (capture.properties.contains("Current.Perspective.available") ? "captured-metadata" : "derived-from-projection")
            << " near=" << camera.nearPlane << " far=" << camera.farPlane << " fov=" << camera.verticalFovRadians << std::endl;
    }
    std::pair<Bytes, Bytes> render(const Capture& capture, mcdr_vanilla_frame_input frame) {
        frame.settings.dlssPreset = preset;
        mcdr_world_output_input world{};
        world.schemaVersion = MCDR_WORLD_OUTPUT_SCHEMA_VERSION;
        world.structSize = sizeof(world);
        world.identity = frame.identity;
        world.motionCoverage = static_cast<uint32_t>(capture.integer("motionCoverage"));
        world.motionGuideCoverage = static_cast<uint32_t>(capture.integer("motionGuideCoverage"));
        world.color = {color, 0, 1};
        world.depth = {depth, 0, 1};
        world.motion = {motion, 0, 1};
        world.exposure = {exposure, 0, 1};
        world.reconstructionOutput = {output, 0, 1};
        world.scene = {scene, 0, 1};
        world.dlssPostSharpness = std::stof(capture.setting("dlssPostSharpness"));
        MSG message{};
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        invokeChecked("Begin frame list", [&] { mcdr_dx12BeginCommandList(context); });
        requireResult(mcdr_dx12BeginDisplayFrame(context), "BeginDisplayFrame");
        mcdr_dx12ReflexFrameBegin();
        requireResult(mcdr_dx12VanillaBeginFrame(context, &frame), "VanillaBeginFrame");
        requireResult(mcdr_dx12VanillaWorldReady(context, &frame.identity), "VanillaWorldReady");
        const auto version = frame.identity.resourceVersion;
        if (replayColor != ReplayColor::GammaUnorm8) {
            const uint32_t encoding = replayColor == ReplayColor::LinearFloat32 ? 3u : 1u;
            if (frame.colorEncoding != encoding || frame.preExposure != 1.0f) throw std::runtime_error("Prepared replay requires matching encoding and unit pre-exposure");
            mcdr_prepared_frame prepared{};
            prepared.schemaVersion = MCDR_PREPARED_FRAME_SCHEMA_VERSION;
            prepared.structSize = sizeof(prepared);
            prepared.identity = frame.identity;
            prepared.historyGeneration = frame.historyGeneration;
            prepared.availability = prepared.required = 15;
            prepared.consumer = 2;
            prepared.motionCoverage = world.motionCoverage;
            prepared.motionGuideCoverage = world.motionGuideCoverage;
            prepared.color = world.color;
            prepared.depth = world.depth;
            prepared.motion = world.motion;
            prepared.exposure = world.exposure;
            requireResult(mcdr_dx12VanillaPublishPreparedFrame(context, &prepared), "Publish prepared frame with unit exposure");
            mcdr_reconstruction_request request{};
            request.schemaVersion = MCDR_VANILLA_FRAME_SCHEMA_VERSION;
            request.structSize = sizeof(request);
            request.identity = frame.identity;
            request.consumer = 2;
            request.output = world.reconstructionOutput;
            requireResult(mcdr_dx12VanillaEvaluateReconstruction(context, &request), "Evaluate prepared reconstruction");
            mcdr_vanilla_resource_use transferUses[] = {
                {output, version, 0, 1, 1, replayColor == ReplayColor::LinearFloat32 ? 0x800u : 0x80u,
                    replayColor == ReplayColor::LinearFloat32 ? 0u : 0x4u, 0},
                {scene, version, 0, 1, 2, replayColor == ReplayColor::LinearFloat32 ? 0x400u : 0x4u,
                    replayColor == ReplayColor::LinearFloat32 ? 0u : 0x4u, 0}
            };
            void* transferToken{};
            requireResult(mcdr_dx12VanillaBeginDeclaredPass(context, 0x7E020003, transferUses, 2,
                replayColor == ReplayColor::LinearFloat32 ? 0u : 3u, &transferToken), "Begin prepared scene transfer");
            if (replayColor == ReplayColor::LinearFloat32) {
                invokeChecked("Copy float output to float scene", [&] {
                    mcdr_dx12CopyTextureToTexture(context, output, scene, 0, 0, 0, 0, 0, 0, width, height);
                });
            } else {
                requireResult(world.dlssPostSharpness > 0.0f
                    ? mcdr_dx12SharpenTextureToTexture(context, output, scene, world.dlssPostSharpness)
                    : mcdr_dx12BlitTextureToTexture(context, output, scene), "Transfer prepared output to scene");
            }
            requireResult(mcdr_dx12VanillaFinishDeclaredPass(context, transferToken), "Finish prepared scene transfer");
        } else {
            requireResult(mcdr_dx12VanillaPrepareWorldOutput(context, &world), "VanillaPrepareWorldOutput frame=" + std::to_string(frame.identity.realFrameId));
        }
        mcdr_vanilla_resource_use uses[] = {
            {output, version, 0, 1, 1, 0x800, 0, 0}, {outputRead, version, 0, 1, 2, 0x400, 0x400, 0},
            {scene, version, 0, 1, 1, 0x800, 0, 0}, {sceneRead, version, 0, 1, 2, 0x400, 0x400, 0},
            {exposure, version, 0, 1, 1, 0x800, 0, 0}, {exposureRead, version, 0, 1, 2, 0x400, 0x400, 0}
        };
        void* passToken{};
        requireResult(mcdr_dx12VanillaBeginDeclaredPass(context, 0x7E020002, uses, 6, 0, &passToken), "Begin readback pass");
        invokeChecked("Read output", [&] { mcdr_dx12CopyTextureToBuffer(context, output, 0, 0, 0, 0, width, height, outputRead, 0); });
        invokeChecked("Read scene", [&] { mcdr_dx12CopyTextureToBuffer(context, scene, 0, 0, 0, 0, width, height, sceneRead, 0); });
        invokeChecked("Read exposure", [&] { mcdr_dx12CopyTextureToBuffer(context, exposure, 0, 0, 0, 0, 1, 1, exposureRead, 0); });
        requireResult(mcdr_dx12VanillaFinishDeclaredPass(context, passToken), "Finish readback pass");
        requireResult(mcdr_dx12VanillaGuiReady(context, &frame.identity), "VanillaGuiReady");
        requireResult(mcdr_dx12AcquireSurface(surface), "AcquireSurface");
        if (!mcdr_dx12GetBackBufferHandle(surface)) throw std::runtime_error("Acquired surface has no backbuffer");
        mcdr_vanilla_resource_use blitUse{scene, version, 0, 1, 1, 0xC0, 0, 0};
        requireResult(mcdr_dx12VanillaBeginDeclaredPass(context, 0x20003, &blitUse, 1, 3, &passToken), "Begin surface blit pass");
        invokeChecked("BlitSurface", [&] { mcdr_dx12BlitSurface(context, surface, scene); });
        requireResult(mcdr_dx12VanillaFinishDeclaredPass(context, passToken), "Finish surface blit pass");
        submitAndWait();
        auto result = std::pair{readBuffer(outputRead, colorTransferBytes), readBuffer(sceneRead, colorTransferBytes)};
        auto exposureBytes = readBuffer(exposureRead, 4);
        std::memcpy(&lastExposure, exposureBytes.data(), 4);
        if (!std::isfinite(lastExposure) || lastExposure <= 0) throw std::runtime_error("Invalid GPU exposure");
        if (replayColor != ReplayColor::GammaUnorm8 && lastExposure != 1.0f) throw std::runtime_error("Prepared replay GPU exposure changed from exactly 1.0");
        ++presentCalls;
        invokeChecked("PresentSurface", [&] { mcdr_dx12PresentSurface(surface); });
        if (mcdr_dx12GetBackBufferHandle(surface)) throw std::runtime_error("PresentSurface did not release acquired backbuffer");
        ++consumedPresents;
        if (IsWindowVisible(window)) throw std::runtime_error("Present unexpectedly revealed hidden window");
        requireResult(mcdr_dx12VanillaResetFrame(context), "VanillaResetFrame");
        if (replayColor == ReplayColor::LinearFloat32) {
            auto encoded = std::pair{linearFloatsToSrgbBytes(result.first), linearFloatsToSrgbBytes(result.second)};
            lastLinearOutput = std::move(result.first);
            return encoded;
        }
        return result;
    }
    void destroy() {
        mcdr_dx12SrDestroy();
        requireResult(mcdr_dx12DestroySurfaceChecked(surface), "DestroySurfaceChecked");
        mcdr_dx12DestroyCommandEncoder(context);
        for (auto iterator = resources.rbegin(); iterator != resources.rend(); ++iterator) mcdr_dx12DestroyResource(*iterator);
        mcdr_dx12DestroyDevice();
        DestroyWindow(window);
        UnregisterClassW(L"IllusionDlaaReplayHidden", GetModuleHandleW(nullptr));
    }
};
struct RecordedFrame {
    std::string phase;
    int phaseFrame;
    uint64_t frameId, fence;
    float exposure;
    Bytes output, scene;
};
void saveReports(const fs::path& directory, const std::vector<RecordedFrame>& frames, const std::vector<size_t>& candidates, const Capture& first, const Capture& second) {
    const int width = first.width, height = first.height;
    std::vector<size_t> points = candidates;
    for (auto pixel : {size_t(315 * width + 806), size_t(163 * width + 532), size_t(203 * width + 479)})
        if (pixel < size_t(width) * height && std::find(points.begin(), points.end(), pixel) == points.end()) points.push_back(pixel);
    const auto& baseline = frames.back();
    std::ofstream curves(directory / "point-curves.tsv");
    curves << "phase\tphaseFrame\tframeId\tfence\tx\ty\tcandidate\tr\tg\tb\tsceneR\tsceneG\tsceneB\tbaselineR\tbaselineG\tbaselineB\tmaxErrorToResetB64\tmaxErrorToOldInput\tmaxErrorToNewInput\n";
    std::ofstream totals(directory / "convergence.tsv");
    totals << "phase\tphaseFrame\tframeId\tfence\tmeanRGBErrorToResetB64\tmaxRGBErrorToResetB64\tpixelsErrorOver3\tpixelsErrorOver16\tcandidatesMeanMaxError\tcandidatesMaxError\tcandidatesOver3\tcandidatesOver16\tsceneMeanRGBError\texposure\n";
    for (const auto& frame : frames) {
        double sum = 0, sceneSum = 0, candidateSum = 0;
        int maximum = 0, count3 = 0, count16 = 0, candidateMax = 0, candidate3 = 0, candidate16 = 0;
        for (size_t pixel = 0; pixel < width * height; ++pixel) {
            int error = difference(frame.output, baseline.output, pixel);
            maximum = std::max(maximum, error);
            count3 += error > 3;
            count16 += error > 16;
            for (int channel = 0; channel < 3; ++channel) {
                sum += std::abs(int(frame.output[pixel * 4 + channel]) - int(baseline.output[pixel * 4 + channel]));
                sceneSum += std::abs(int(frame.scene[pixel * 4 + channel]) - int(baseline.scene[pixel * 4 + channel]));
            }
        }
        for (auto pixel : candidates) {
            int error = difference(frame.output, baseline.output, pixel);
            candidateSum += error;
            candidateMax = std::max(candidateMax, error);
            candidate3 += error > 3;
            candidate16 += error > 16;
        }
        totals << frame.phase << '\t' << frame.phaseFrame << '\t' << frame.frameId << '\t' << frame.fence << '\t'
            << sum / (width * height * 3) << '\t' << maximum << '\t' << count3 << '\t' << count16 << '\t'
            << candidateSum / candidates.size() << '\t' << candidateMax << '\t' << candidate3 << '\t' << candidate16 << '\t' << sceneSum / (width * height * 3) << '\t' << frame.exposure << '\n';
        for (auto pixel : points) {
            curves << frame.phase << '\t' << frame.phaseFrame << '\t' << frame.frameId << '\t' << frame.fence << '\t' << pixel % width << '\t' << pixel / width << '\t'
                << (std::find(candidates.begin(), candidates.end(), pixel) != candidates.end());
            for (const Bytes* bytes : {&frame.output, &frame.scene, &baseline.output})
                for (int channel = 0; channel < 3; ++channel) curves << '\t' << int((*bytes)[pixel * 4 + channel]);
            curves << '\t' << difference(frame.output, baseline.output, pixel) << '\t' << difference(frame.output, first.color, pixel) << '\t' << difference(frame.output, second.color, pixel) << '\n';
        }
        if ((frame.phase == "A" && frame.phaseFrame == 64) || (frame.phase == "resetB" && frame.phaseFrame == 64) ||
            (frame.phase == "B" && (frame.phaseFrame & (frame.phaseFrame - 1)) == 0)) {
            saveBytes(directory / (frame.phase + std::to_string(frame.phaseFrame) + "-output.bin"), frame.output);
            saveBytes(directory / (frame.phase + std::to_string(frame.phaseFrame) + "-scene.bin"), frame.scene);
        }
    }
    if (!curves || !totals) throw std::runtime_error("Report write failed");
}
enum class ReplayMode { TwoFrame, Sequence, SequenceCycle, SequenceOpaqueAlpha, SequenceResetHistory, SequenceLinearColor, SequencePreparedLdr };

bool sameCameraPose(const mcdr_raster_camera& first, const mcdr_raster_camera& second) {
    return std::equal(std::begin(first.origin), std::end(first.origin), std::begin(second.origin)) &&
        first.yaw == second.yaw && first.pitch == second.pitch && first.flags == second.flags &&
        first.nearPlane == second.nearPlane && first.farPlane == second.farPlane && first.verticalFovRadians == second.verticalFovRadians &&
        std::equal(std::begin(first.projection), std::end(first.projection), std::begin(second.projection)) &&
        std::equal(std::begin(first.viewRotation), std::end(first.viewRotation), std::begin(second.viewRotation));
}

void validateSequence(const fs::path& directory, const std::vector<Capture>& captures) {
    auto session = readProperties(directory / "session.properties");
    if (session.at("complete64") != "true" || session.at("failedFrames") != "0") throw std::runtime_error("Capture session is incomplete or contains failed frames");
    std::set<uint64_t> phases;
    auto first = captures.front().frameInputs();
    auto prior = first;
    for (size_t index = 0; index < captures.size(); ++index) {
        const auto& capture = captures[index];
        if (!capture.properties.contains("Current.Perspective.available") || !capture.properties.contains("Previous.Perspective.available"))
            throw std::runtime_error("Continuous sequence requires captured Current and Previous perspective metadata");
        auto frame = capture.frameInputs();
        if (capture.width != captures.front().width || capture.height != captures.front().height || capture.integer("abiVersion") != 13 ||
            frame.identity.worldEpoch != first.identity.worldEpoch || frame.identity.configVersion != first.identity.configVersion ||
            frame.identity.resourceVersion != first.identity.resourceVersion || frame.historyGeneration != first.historyGeneration ||
            frame.identity.realFrameId != first.identity.realFrameId + index || frame.jitterPhaseIndex != first.jitterPhaseIndex + index ||
            capture.integer("capture.ordinal") != index + 1 || capture.integer("capture.jitterPhase16") != frame.jitterPhaseIndex % 16)
            throw std::runtime_error("Incompatible sequence metadata at frame-" + std::to_string(index + 1));
        if (index && (frame.frameTimeNanos <= prior.frameTimeNanos || !sameCameraPose(frame.previous, prior.current) ||
            frame.previous.jitterX != prior.current.jitterX || frame.previous.jitterY != prior.current.jitterY ||
            !std::equal(std::begin(frame.previous.rasterProjection), std::end(frame.previous.rasterProjection), std::begin(prior.current.rasterProjection))))
            throw std::runtime_error("Previous camera or timestamp breaks continuity at frame-" + std::to_string(index + 1));
        phases.insert(frame.jitterPhaseIndex % 16);
        prior = frame;
    }
    if (captures.size() != 64 || phases.size() != 16) throw std::runtime_error("Sequence requires all 64 frames and 16 jitter phases");
}

std::string staticCycleFailure(const std::vector<Capture>& captures) {
    if (captures.size() != 64) return "Requires a complete 64-frame capture";
    const auto anchor = captures.back().camera("Current");
    std::set<std::pair<double, double>> jitters;
    std::set<uint64_t> phases;
    for (size_t index = 48; index < 64; ++index) {
        const auto current = captures[index].camera("Current"), previous = captures[index].camera("Previous");
        if (!sameCameraPose(anchor, current) || !sameCameraPose(anchor, previous)) return "Last 16 captured frames do not have a stationary camera";
        phases.insert(captures[index].integer("JitterPhaseIndex") % 16);
        jitters.emplace(current.jitterX, current.jitterY);
    }
    const auto firstPrevious = captures[48].camera("Previous");
    if (firstPrevious.jitterX != anchor.jitterX || firstPrevious.jitterY != anchor.jitterY)
        return "Captured jitter does not close the 16-phase cycle";
    if (phases.size() != 16 || jitters.size() != 16) return "Last 16 frames lack 16 distinct captured jitter samples";
    return {};
}

struct RgbComparison {
    double mean{};
    int maximum{};
    size_t over3{}, over16{}, maximumPixel{};
};

RgbComparison compareRgb(const Bytes& output, const Bytes& reference) {
    if (output.size() != reference.size() || output.empty() || output.size() % 4) throw std::runtime_error("RGB comparison extent mismatch");
    RgbComparison comparison;
    for (size_t pixel = 0; pixel < output.size() / 4; ++pixel) {
        int error = difference(output, reference, pixel);
        comparison.over3 += error > 3;
        comparison.over16 += error > 16;
        if (error > comparison.maximum) { comparison.maximum = error; comparison.maximumPixel = pixel; }
        for (int channel = 0; channel < 3; ++channel)
            comparison.mean += std::abs(int(output[pixel * 4 + channel]) - int(reference[pixel * 4 + channel]));
    }
    comparison.mean /= (output.size() / 4) * 3;
    return comparison;
}

void replaySequence(Replay& replay, const std::vector<Capture>& captures, const fs::path& directory, ReplayMode mode) {
    std::ofstream comparisons(directory / "live-comparison.tsv"), points(directory / "live-points.tsv"), explanation(directory / "sequence-report.txt");
    comparisons << "ordinal\trealFrameId\tjitterPhaseIndex\tphase16\tjitterX\tjitterY\tmetadataFrameDeltaMs\tfence\texposure\tmeanRGBErrorToLive\tmaxRGBErrorToLive\tpixelsOver3\tpixelsOver16\tmaxErrorX\tmaxErrorY\n";
    points << "ordinal\tx\ty\treplayR\treplayG\treplayB\tliveR\tliveG\tliveB\tmaxRGBError\n";
    explanation << "ABI13, Streamline, explicit DLAA preset=" << replay.preset << "; captured source preset 0/default or 11 only, unchanged.\n"
        << "Replay starts with reset history and follows all 64 captured Current/Previous cameras, jitter, frame IDs and timestamps.\n"
        << "Current/Previous near, far and vertical FOV come from captured Perspective metadata, without projection derivation.\n"
        << "Differences to live reconstructionOutput can include history from before the capture, which this replay does not contain.\n"
        << "FrameDeltaMs is capture metadata; the actual SDK delta is unknown and no elapsed SDK time is inferred.\n"
        << "All output.bin and scene.bin files are compact little-endian RGBA8_UNORM, top-left origin, " << replay.width << "x" << replay.height << ".\n"
        << "Frame output files contain every pixel's numerical values; live-comparison.tsv compares RGB, excluding alpha.\n";
    if (mode == ReplayMode::SequenceOpaqueAlpha) {
        explanation << "Experimental variable: every uploaded color alpha byte is forced to 255, including the initial preparation input.\n"
            << "Captured files remain unchanged. Color RGB, depth, motion, camera, jitter, frame metadata and reset start are unchanged; explicit preset is recorded above.\n"
            << "The reference is the original live reconstructionOutput captured with the original input alpha.\n"
            << "This comparison measures whether input alpha affects reconstructed RGB; it makes no visual-quality claim.\n";
    } else {
        explanation << "Input alpha: original captured bytes, unchanged.\n";
    }
    if (mode == ReplayMode::SequenceResetHistory) {
        explanation << "Experimental variable: increment historyGeneration before every frame to request a real SDK history reset.\n"
            << "All captured textures, cameras, jitter, frame IDs and timestamps remain unchanged.\n"
            << "This isolates per-frame spatial reconstruction from retained SDK history; it is not a production rendering mode.\n";
    }
    if (mode == ReplayMode::SequenceLinearColor) {
        explanation << "Experimental variable: CPU decodes captured gamma RGB with standard sRGB EOTF into RGBA32_FLOAT (native ordinal47); alpha is normalized by 255.\n"
            << "EOTF: s<=0.04045 ? s/12.92 : ((s+0.055)/1.055)^2.4. SDK output and scene use RGBA32_FLOAT.\n"
            << "Frame colorEncoding=3, preExposure=1.0. A prepared frame publishes the verified unit exposure texture directly to the same native reconstruction adapter.\n"
            << "VanillaPrepareWorldOutput automatic HDR exposure generation is bypassed; every frame reads GPU exposure and requires exactly 1.0.\n"
            << "Float format also enables Streamline colorBuffersHDR; precision, transfer function and this SDK HDR/LDR path change together.\n"
            << "output.bin and scene.bin encode readback RGB with standard sRGB OETF and round to RGBA8; alpha is only normalized/quantized.\n"
            << "OETF: l<=0.0031308 ? 12.92*l : 1.055*l^(1/2.4)-0.055, clamped to [0,1] for RGBA8 reports.\n"
            << "sequence-N-output-linear-f32.bin stores original unclamped little-endian RGBA32_FLOAT readback for precision analysis.\n"
            << "The existing native post-sharpen pipeline supports RGBA8 only, so float scene is an unsharpened copy of SDK output. Output analysis uses the pre-sharpen SDK result in every mode.\n"
            << "Depth, motion, camera, jitter, captured frame metadata and initial reset remain unchanged.\n";
    }
    if (mode == ReplayMode::SequencePreparedLdr) {
        explanation << "Control: captured gamma RGBA8_UNORM textures, frame colorEncoding=1, preExposure=1.0.\n"
            << "Prepared frame directly publishes the verified unit exposure texture through the same publish/evaluate path as SequenceLinearColor.\n"
            << "Automatic exposure generation is bypassed; every frame requires GPU exposure exactly 1.0. All captured input texture bytes remain unchanged.\n";
    }
    for (size_t index = 0; index < captures.size(); ++index) {
        const auto& capture = captures[index];
        auto frame = capture.frameInputs();
        if (mode == ReplayMode::SequenceResetHistory) frame.historyGeneration += index;
        if (mode == ReplayMode::SequenceLinearColor) frame.colorEncoding = 3;
        if (index) replay.uploadCapture(capture);
        auto result = replay.render(capture, frame);
        auto differenceToLive = compareRgb(result.first, capture.output);
        comparisons << index + 1 << '\t' << frame.identity.realFrameId << '\t' << frame.jitterPhaseIndex << '\t' << frame.jitterPhaseIndex % 16
            << '\t' << frame.current.jitterX << '\t' << frame.current.jitterY << '\t' << frame.frameDeltaMs << '\t' << replay.lastFence
            << '\t' << replay.lastExposure << '\t' << differenceToLive.mean << '\t' << differenceToLive.maximum << '\t' << differenceToLive.over3
            << '\t' << differenceToLive.over16 << '\t' << differenceToLive.maximumPixel % replay.width << '\t' << differenceToLive.maximumPixel / replay.width << '\n';
        std::set<size_t> selected{differenceToLive.maximumPixel};
        for (const auto coordinate : {std::pair{806, 315}, std::pair{532, 163}, std::pair{479, 203}})
            if (coordinate.first < replay.width && coordinate.second < replay.height) selected.insert(size_t(coordinate.second) * replay.width + coordinate.first);
        for (auto pixel : selected) {
            points << index + 1 << '\t' << pixel % replay.width << '\t' << pixel / replay.width;
            for (const Bytes* values : {static_cast<const Bytes*>(&result.first), &capture.output})
                for (int channel = 0; channel < 3; ++channel) points << '\t' << int((*values)[pixel * 4 + channel]);
            points << '\t' << difference(result.first, capture.output, pixel) << '\n';
        }
        auto name = "sequence-" + std::to_string(index + 1);
        saveBytes(directory / (name + "-output.bin"), result.first);
        saveBytes(directory / (name + "-scene.bin"), result.second);
        if (mode == ReplayMode::SequenceLinearColor) saveBytes(directory / (name + "-output-linear-f32.bin"), replay.lastLinearOutput);
        std::cout << "completed " << name << " sourceFrame=" << frame.identity.realFrameId << " phase16=" << frame.jitterPhaseIndex % 16
            << " meanRGBErrorToLive=" << differenceToLive.mean << " fence=" << replay.lastFence << std::endl;
    }
    const auto cycleFailure = staticCycleFailure(captures);
    explanation << "Last-16-frame cycle validation: " << (cycleFailure.empty() ? "passed" : cycleFailure) << '\n';
    if (mode == ReplayMode::SequenceCycle && cycleFailure.empty()) {
        explanation << "Cycle experiment: captured frames 49..64 replayed in order for 4 retained-history cycles, then 4 reset-history cycles.\n"
            << "Frame IDs/timestamps/phase indices advance for replay bookkeeping; cameras, actual jitter and texture inputs come from those 16 captures.\n"
            << "Captured animated content is periodic in this experiment; these are not newly rendered frames.\n";
        std::vector<Bytes> outputs;
        auto lastFrame = captures.back().frameInputs();
        for (int branch = 0; branch < 2; ++branch) {
            for (int index = 0; index < 64; ++index) {
                const auto& capture = captures[48 + index % 16];
                auto frame = capture.frameInputs();
                frame.identity.realFrameId = ++lastFrame.identity.realFrameId;
                frame.previousRealFrameId = frame.identity.realFrameId - 1;
                frame.frameTimeNanos = lastFrame.frameTimeNanos + uint64_t(double(frame.frameDeltaMs) * 1000000.0);
                frame.jitterPhaseIndex = lastFrame.jitterPhaseIndex + 1;
                frame.historyGeneration = captures.back().integer("HistoryGeneration") + branch;
                replay.uploadCapture(capture);
                auto result = replay.render(capture, frame);
                auto name = std::string(branch == 0 ? "retained-cycle-" : "reset-cycle-") + std::to_string(index + 1);
                saveBytes(directory / (name + "-output.bin"), result.first);
                outputs.push_back(std::move(result.first));
                lastFrame = frame;
            }
        }
        std::ofstream convergence(directory / "cycle-convergence.tsv");
        convergence << "branch\tcycle\tphaseOffset\tsourceOrdinal\tmeanRGBErrorToResetCycle4SamePhase\tmaxRGBError\tpixelsOver3\tpixelsOver16\n";
        for (size_t index = 0; index < outputs.size(); ++index) {
            const auto comparison = compareRgb(outputs[index], outputs[112 + index % 16]);
            convergence << (index < 64 ? "retained" : "reset") << '\t' << (index % 64) / 16 + 1 << '\t' << index % 16 + 1
                << '\t' << 49 + index % 16 << '\t' << comparison.mean << '\t' << comparison.maximum << '\t' << comparison.over3 << '\t' << comparison.over16 << '\n';
        }
        if (!convergence) throw std::runtime_error("Cycle report write failed");
    } else {
        explanation << "Cycle experiment skipped: " << (mode != ReplayMode::SequenceCycle ? "not requested" : cycleFailure) << '\n';
    }
    if (!comparisons || !points || !explanation) throw std::runtime_error("Sequence report write failed");
}

void replayTwoFrames(Replay& replay, const std::vector<Capture>& captures, const fs::path& directory) {
    const auto& first = captures[0];
    const auto& second = captures[1];
    auto candidates = candidatePoints(first, second);
    if (candidates.empty()) throw std::runtime_error("Legacy candidate selection is empty");
    std::cout << "Legacy sequence=A64 B64 resetB64; candidates=" << candidates.size() << "; repeats one captured jitter sample per phase\n"
        << "This experiment does not reproduce newly rendered 16-phase inputs; comparison is resetB64, not input color." << std::endl;
    std::ofstream explanation(directory / "legacy-report.txt");
    explanation << "A64 -> B64 -> resetB64 repeats one captured jittered image per phase.\n"
        << "This fixed-input history-decay experiment does not establish behavior with newly rendered 16-phase inputs.\n"
        << "Actual SDK delta is unknown; metadata delta is not SDK elapsed time.\n";
    for (size_t index = 0; index < captures.size(); ++index)
        for (const auto& prefix : {std::string("Current"), std::string("Previous")})
            explanation << "frame-" << index + 1 << ' ' << prefix << " lensSource="
                << (captures[index].properties.contains(prefix + ".Perspective.available") ? "captured-metadata" : "derived-from-projection") << '\n';
    if (!explanation) throw std::runtime_error("Legacy report write failed");
    std::vector<RecordedFrame> frames;
    uint64_t frameId = 10000, timeNanos = 100000000000ULL;
    auto previous = first.camera("Previous");
    for (int phase = 0; phase < 3; ++phase) {
        const auto& capture = phase == 0 ? first : second;
        if (phase == 1) replay.uploadCapture(second);
        const std::string phaseName = phase == 0 ? "A" : phase == 1 ? "B" : "resetB";
        for (int index = 1; index <= 64; ++index) {
            timeNanos += uint64_t(std::stod(capture.properties.at("FrameDeltaMs")) * 1000000.0);
            auto frame = capture.frameInputs();
            frame.identity = {1, frameId, 1, 1};
            frame.previousRealFrameId = frameId - 1;
            frame.historyGeneration = phase == 2 ? 2 : 1;
            frame.frameTimeNanos = timeNanos;
            frame.jitterPhaseIndex = frameId - 1;
            frame.previous = previous;
            auto result = replay.render(capture, frame);
            frames.push_back({phaseName, index, frameId, replay.lastFence, replay.lastExposure, std::move(result.first), std::move(result.second)});
            previous = frame.current;
            ++frameId;
            if ((index & (index - 1)) == 0) std::cout << "completed " << phaseName << index << " fence=" << replay.lastFence << std::endl;
        }
        char note[8192]{};
        mcdr_dx12SrQuery(note, sizeof(note));
        std::cout << phaseName << " " << note << std::endl;
    }
    saveReports(directory, frames, candidates, first, second);
}

int main(int argc, char** argv) {
    try {
        if (argc < 3) throw std::runtime_error("Expected capture-directory report-directory [--sequence|--sequence-cycle|--sequence-opaque-alpha|--sequence-reset-history|--sequence-linear-color|--sequence-prepared-ldr] [--preset=10|11|12|13]");
        std::optional<ReplayMode> selectedMode;
        std::optional<int32_t> presetOverride;
        for (int argumentIndex = 3; argumentIndex < argc; ++argumentIndex) {
            const std::string argument = argv[argumentIndex];
            if (argument.starts_with("--preset=")) {
                const auto value = argument.substr(9);
                if (presetOverride || (value != "10" && value != "11" && value != "12" && value != "13"))
                    throw std::runtime_error("Expected one --preset=10|11|12|13");
                presetOverride = std::stoi(value);
                continue;
            }
            if (selectedMode) throw std::runtime_error("Expected only one replay mode");
            if (argument == "--sequence") selectedMode = ReplayMode::Sequence;
            else if (argument == "--sequence-cycle") selectedMode = ReplayMode::SequenceCycle;
            else if (argument == "--sequence-opaque-alpha") selectedMode = ReplayMode::SequenceOpaqueAlpha;
            else if (argument == "--sequence-reset-history") selectedMode = ReplayMode::SequenceResetHistory;
            else if (argument == "--sequence-linear-color") selectedMode = ReplayMode::SequenceLinearColor;
            else if (argument == "--sequence-prepared-ldr") selectedMode = ReplayMode::SequencePreparedLdr;
            else throw std::runtime_error("Unknown replay argument: " + argument);
        }
        const auto mode = selectedMode.value_or(ReplayMode::TwoFrame);
        const int32_t preset = presetOverride.value_or(11);
        const fs::path captureDirectory = fs::absolute(argv[1]), reportDirectory = fs::absolute(argv[2]);
        fs::create_directories(reportDirectory);
        std::vector<Capture> captures;
        for (int index = 1; index <= (mode == ReplayMode::TwoFrame ? 2 : 64); ++index) captures.emplace_back(captureDirectory / ("frame-" + std::to_string(index)));
        for (const auto& capture : captures) {
            capture.frameInputs();
            if (capture.width != captures.front().width || capture.height != captures.front().height) throw std::runtime_error("Capture resized");
        }
        if (mode != ReplayMode::TwoFrame) validateSequence(captureDirectory, captures);
        std::ofstream overrides(reportDirectory / "experiment-overrides.properties");
        overrides << "effectivePreset=" << preset << "\npresetSelection=" << (presetOverride ? "explicit-cli" : "default-11")
            << "\ncapturedFirstPreset=" << captures.front().setting("dlssPreset")
            << "\ncapturedPresetPolicy=original 0/11 accepted; captured files are unchanged\n";
        if (!overrides) throw std::runtime_error("Experiment override report write failed");
        std::cout << "ABI=" << mcdrABIVersion() << " size=" << captures.front().width << "x" << captures.front().height
            << " DLAA=4 preset=" << preset << " providerMasks=0 backend=Streamline mode=" << (mode == ReplayMode::TwoFrame ? "two-frame" :
                mode == ReplayMode::SequenceOpaqueAlpha ? "sequence-opaque-alpha" :
                mode == ReplayMode::SequenceResetHistory ? "sequence-reset-history" :
                mode == ReplayMode::SequenceLinearColor ? "sequence-linear-color" :
                mode == ReplayMode::SequencePreparedLdr ? "sequence-prepared-ldr" : "sequence") << std::endl;
        if (mcdrABIVersion() != 13) throw std::runtime_error("Unexpected ABI");
        requireResult(mcdr_dx12SetDlssVendorDir(reportDirectory.string().c_str()), "SetDlssVendorDir");
        char note[8192]{};
        if (mcdr_dx12CreateDevice(note, sizeof(note)) <= 0 || std::string(note).starts_with("ERROR:"))
            throw std::runtime_error(std::string("CreateDevice: ") + note);
        std::cout << note << std::endl;
        mcdr_dx12QueryDlss(note, sizeof(note));
        std::cout << note << std::endl;
        Replay replay(captures.front().width, captures.front().height,
            mode == ReplayMode::SequenceOpaqueAlpha ? InputAlpha::Opaque : InputAlpha::Captured,
            mode == ReplayMode::SequenceLinearColor ? ReplayColor::LinearFloat32 :
            mode == ReplayMode::SequencePreparedLdr ? ReplayColor::PreparedGammaUnorm8 : ReplayColor::GammaUnorm8, preset);
        replay.create();
        requireResult(mcdr_dx12PrewarmFrameprep(replay.context, 1, 0), "PrewarmFrameprep");
        requireResult(mcdr_dx12PrewarmDisplayFrame(replay.context, 1), "PrewarmDisplayFrame");
        replay.uploadCapture(captures.front());
        replay.prepare(captures.front());
        if (mode == ReplayMode::TwoFrame) replayTwoFrames(replay, captures, reportDirectory);
        else replaySequence(replay, captures, reportDirectory, mode);
        mcdr_dx12QueryDlss(note, sizeof(note));
        std::cout << "final " << note << "\nreflexFrameBeginCalls=" << replay.presentCalls + 1 << " replayFrames=" << replay.presentCalls << " successfulFenceWaits=" << replay.fenceWaits
            << " presentCalls=" << replay.presentCalls << " consumedPresents=" << replay.consumedPresents
            << " windowVisible=" << IsWindowVisible(replay.window) << "\nSDK actual frameTimeDelta unknown; capture metadata delta is not forwarded through mcdr_dlss_input" << std::endl;
        replay.destroy();
        std::cout << "SUCCESS reports=" << reportDirectory.string() << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED " << error.what() << std::endl;
        ExitProcess(1);
    }
}
