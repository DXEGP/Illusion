#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;
uniform sampler2D DepthBoundsSampler;
layout(push_constant) uniform SourceMapping {
    vec2 SourceUvOffset;
} sourceMapping;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 sourceSize = textureSize(Sampler0, 0);
    vec2 sourceUv = vec2(texCoord.x, 1.0 - texCoord.y) + sourceMapping.SourceUvOffset;
    ivec2 pixelCoords = clamp(ivec2(floor(sourceUv * vec2(sourceSize))), ivec2(0), sourceSize - ivec2(1));
    vec4 accumulatedColor = texelFetch(Sampler0, pixelCoords, 0);
    float sampledTransmittance = sampleTransmittance(pixelCoords, 100000.0, 1.0);
    float coverage = 1.0 - (sampledTransmittance < OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE ? 0.0 : sampledTransmittance);
    if (coverage < 0.00001 && dot(accumulatedColor.rgb, vec3(1.0)) < 0.00001) {
        discard;
    }
    float normalization = accumulatedColor.a > 0.00001 ? coverage / accumulatedColor.a : 1.0;
    fragColor = vec4(accumulatedColor.rgb * normalization, coverage);
    gl_FragDepth = texelFetch(DepthBoundsSampler, pixelCoords, 0).b;
}
