#ifndef MCDR_OIT_TILE_ENVELOPE_V1
#define MCDR_OIT_TILE_ENVELOPE_V1

struct McdrTileEnvelopeV1 {
    float4 pixelBounds;
    uint4 identity;
    uint4 draw;
    uint4 properties;
};

McdrTileEnvelopeV1 McdrTileEmptyEnvelopeV1(uint4 identity, uint4 draw) {
    McdrTileEnvelopeV1 envelope = (McdrTileEnvelopeV1)0;
    envelope.pixelBounds = float4(3.402823466e+38, 3.402823466e+38, -3.402823466e+38, -3.402823466e+38);
    envelope.identity = identity;
    envelope.draw = draw;
    return envelope;
}

void McdrTileIncludeClipV1(inout McdrTileEnvelopeV1 envelope, float4 clip) {
    if (!all(isfinite(clip)) || clip.w <= 0.000001 || clip.z < 0.0 || clip.z > clip.w) {
        envelope.properties.x |= 1u;
        return;
    }
    float2 ndc = clip.xy / clip.w;
    float2 pixel = McdrTileSourceViewport.xy + float2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5) *
        McdrTileSourceViewport.zw;
    if (!all(isfinite(pixel))) {
        envelope.properties.x |= 1u;
        return;
    }
    envelope.pixelBounds.xy = min(envelope.pixelBounds.xy, pixel);
    envelope.pixelBounds.zw = max(envelope.pixelBounds.zw, pixel);
}

#endif
