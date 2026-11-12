cbuffer TerrainGlobals : register(b8) {
    uint terrainCapacity;
    uint terrainCullEnabled;
    uint terrainSectionCount;
    uint terrainReserved;
    float4 terrainVrsBand;
};
RWByteAddressBuffer terrainVisible : register(u0);
RWByteAddressBuffer terrainArguments : register(u1);

[numthreads(1, 1, 1)]
void InitializeTerrain(uint3 thread : SV_DispatchThreadID) {
    terrainVisible.Store4(0u, uint4(0u, 0u, terrainSectionCount, terrainCapacity));
    terrainVisible.Store4(16u, uint4(0u, 0u, 0u, 0u));
}

[numthreads(64, 1, 1)]
void FinalizeTerrainArguments(uint3 thread : SV_DispatchThreadID) {
    uint slice = thread.x;
    uint slots = (terrainCapacity + 4194303u) / 4194304u;
    if (slice >= slots) return;
    uint total = terrainVisible.Load(0u);
    uint errors = terrainVisible.Load(4u);
    if (total > terrainCapacity) {
        uint ignored;
        terrainVisible.InterlockedOr(4u, 2u, ignored);
        errors |= 2u;
    }
    uint base = slice * 4194304u;
    uint count = errors == 0u && total > base ? min(total - base, 4194304u) : 0u;
    uint width = min(count, 256u);
    uint height = count == 0u ? 1u : (count + 255u) / 256u;
    terrainArguments.Store4(slice * 16u, uint4(base, width, height, 1u));
}
