#ifndef MCDR_OIT_TILE_ROUTE_V1
#define MCDR_OIT_TILE_ROUTE_V1

cbuffer McdrTileDrawV1 : register(b0, space3) {
    uint McdrTileSlot;
    uint McdrTileSourcePrimitive;
    uint McdrTileSourceInstance;
    uint McdrTileVisibleMeshlet;
};

cbuffer McdrTileBatchV1 : register(b1, space3) {
    uint McdrTileSchemaVersion;
    uint McdrTileResidentCount;
    uint McdrTileLaneIndex;
    uint McdrTileFlags;
    float4 McdrTileSourceViewport;
    uint4 McdrTileRegions[16];
};

float2 McdrTileLaneOffsetV1() {
    return float2((McdrTileLaneIndex & 1u) != 0u ? 0.25 : -0.25,
        (McdrTileLaneIndex & 2u) != 0u ? 0.25 : -0.25);
}

float4 McdrTileSourcePositionV1(float4 scratchPosition) {
    scratchPosition.xy += float2(McdrTileRegions[McdrTileSlot].xy) - 2.0 + McdrTileLaneOffsetV1();
    return scratchPosition;
}

#endif
