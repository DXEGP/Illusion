#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D NormalizedTransparencySampler;
uniform sampler2D DepthBoundsSampler;
layout(push_constant) uniform SourceMapping {
    vec2 SourceUvOffset;
} sourceMapping;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 sourceSize = textureSize(NormalizedTransparencySampler, 0);
    vec2 sourceUv = vec2(texCoord.x, 1.0 - texCoord.y) + sourceMapping.SourceUvOffset;
    ivec2 pixelCoords = clamp(ivec2(floor(sourceUv * vec2(sourceSize))), ivec2(0), sourceSize - ivec2(1));
    vec4 normalized = texelFetch(NormalizedTransparencySampler, pixelCoords, 0);
    if (all(equal(normalized, vec4(0.0)))) discard;
    fragColor = normalized;
    gl_FragDepth = texelFetch(DepthBoundsSampler, pixelCoords, 0).b;
}
