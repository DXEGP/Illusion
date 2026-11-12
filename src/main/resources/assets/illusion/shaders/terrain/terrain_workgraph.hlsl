ByteAddressBuffer terrainCandidates : register(t15);
ByteAddressBuffer terrainAtlas : register(t16);
RWByteAddressBuffer terrainVisible : register(u0);
cbuffer TerrainGlobals : register(b8) {
    uint terrainCapacity;
    uint terrainCullEnabled;
    uint terrainSectionCount;
    uint terrainReserved;
    float4 terrainVrsBand;
};

struct TerrainEntryRecord {
    uint3 grid : SV_DispatchGrid;
    uint sectionCount;
};
struct TerrainSectionRecord { uint section; };
struct TerrainRangeRecord {
    uint3 grid : SV_DispatchGrid;
    uint section;
};
struct TerrainMeshletRecord {
    uint section;
    uint descriptor;
};

float3 terrainSectionBase(uint section) {
    return float3(asint(terrainCandidates.Load3(section * 40u + 16u)))
        - float3(TERRAIN_CAMERA_BLOCK) + TERRAIN_CAMERA_OFFSET;
}

bool terrainFrustum(float3 minimum, float3 maximum) {
    float3 center = (minimum + maximum) * 0.5;
    float radius = length((maximum - minimum) * 0.5);
    float4 view = mul(float4(center, 1.0), TERRAIN_MODEL_VIEW);
    float4 clip = mul(view, TERRAIN_PROJECTION);
    float4 plane0 = TERRAIN_PROJECTION[0];
    float4 plane1 = TERRAIN_PROJECTION[1];
    float4 plane3 = TERRAIN_PROJECTION[3];
    if (!all(isfinite(clip)) || !isfinite(radius)) return true;
    float slack = 64.0;
    return clip.x + clip.w >= -radius * length(plane0.xyz + plane3.xyz) - slack
        && clip.w - clip.x >= -radius * length(plane0.xyz - plane3.xyz) - slack
        && clip.y + clip.w >= -radius * length(plane1.xyz + plane3.xyz) - slack
        && clip.w - clip.y >= -radius * length(plane1.xyz - plane3.xyz) - slack
        && clip.w >= -radius * length(plane3.xyz) - slack;
}

[Shader("node")]
[NodeLaunch("broadcasting")]
[NodeIsProgramEntry]
[NodeMaxDispatchGrid(65535, 1, 1)]
[NumThreads(64, 1, 1)]
void SectionEntry(DispatchNodeInputRecord<TerrainEntryRecord> input,
    uint dispatchThread : SV_DispatchThreadID,
    [MaxRecords(64)] [NodeID("SectionCull")] NodeOutput<TerrainSectionRecord> output) {
    bool valid = dispatchThread < input.Get().sectionCount;
    ThreadNodeOutputRecords<TerrainSectionRecord> records = output.GetThreadNodeOutputRecords(valid ? 1 : 0);
    if (valid) records.Get().section = dispatchThread;
    records.OutputComplete();
}

[Shader("node")]
[NodeLaunch("thread")]
void SectionCull(ThreadNodeInputRecord<TerrainSectionRecord> input,
    [MaxRecords(1)] [NodeID("MeshletRange")] NodeOutput<TerrainRangeRecord> output) {
    uint section = input.Get().section;
    uint candidate = section * 40u;
    terrainVisible.Store4(32u + section * 32u, terrainCandidates.Load4(candidate));
    terrainVisible.Store4(48u + section * 32u, terrainCandidates.Load4(candidate + 16u));
    uint meshletCount = terrainCandidates.Load(candidate + 12u);
    bool visible = meshletCount != 0u;
    if (visible && terrainCullEnabled != 0u && terrainCandidates.Load(candidate + 36u) != 0u) {
        float3 base = terrainSectionBase(section);
        visible = terrainFrustum(base, base + 16.0);
    }
    ThreadNodeOutputRecords<TerrainRangeRecord> records = output.GetThreadNodeOutputRecords(visible ? 1 : 0);
    if (visible) {
        records.Get().grid = uint3((meshletCount + 63u) / 64u, 1u, 1u);
        records.Get().section = section;
    }
    records.OutputComplete();
}

[Shader("node")]
[NodeLaunch("broadcasting")]
[NodeMaxDispatchGrid(65535, 1, 1)]
[NumThreads(64, 1, 1)]
void MeshletRange(DispatchNodeInputRecord<TerrainRangeRecord> input,
    uint dispatchThread : SV_DispatchThreadID,
    [MaxRecords(64)] [NodeID("MeshletCull")] NodeOutput<TerrainMeshletRecord> output) {
    uint section = input.Get().section;
    uint candidate = section * 40u;
    bool valid = dispatchThread < terrainCandidates.Load(candidate + 12u);
    ThreadNodeOutputRecords<TerrainMeshletRecord> records = output.GetThreadNodeOutputRecords(valid ? 1 : 0);
    if (valid) {
        records.Get().section = section;
        records.Get().descriptor = terrainCandidates.Load(candidate + 8u) + dispatchThread;
    }
    records.OutputComplete();
}

[Shader("node")]
[NodeLaunch("thread")]
void MeshletCull(ThreadNodeInputRecord<TerrainMeshletRecord> input,
    [MaxRecords(1)] [NodeID("EmitVisible")] NodeOutput<TerrainMeshletRecord> output) {
    TerrainMeshletRecord meshlet = input.Get();
    uint2 bounds = terrainAtlas.Load2(meshlet.descriptor * 16u);
    uint directions = bounds.y >> 24u;
    bool bounded = (directions & 128u) == 0u;
    bool visible = true;
    if (terrainCullEnabled != 0u && bounded) {
        float3 base = terrainSectionBase(meshlet.section);
        float3 minimum = base + float3(bounds.x & 255u, (bounds.x >> 8u) & 255u,
            (bounds.x >> 16u) & 255u) / 15.9375;
        float3 maximum = base + float3(bounds.y & 255u, (bounds.y >> 8u) & 255u,
            (bounds.y >> 16u) & 255u) / 15.9375;
        uint visibleDirections = 0u;
        if (minimum.x < 1.0) visibleDirections |= 1u;
        if (maximum.x > -1.0) visibleDirections |= 2u;
        if (minimum.y < 1.0) visibleDirections |= 4u;
        if (maximum.y > -1.0) visibleDirections |= 8u;
        if (minimum.z < 1.0) visibleDirections |= 16u;
        if (maximum.z > -1.0) visibleDirections |= 32u;
        visible = TERRAIN_BACKFACE_CULL == 0 || (directions & 64u) != 0u
            || (directions & visibleDirections) != 0u;
        if (visible) visible = terrainFrustum(minimum, maximum);
    }
    ThreadNodeOutputRecords<TerrainMeshletRecord> records = output.GetThreadNodeOutputRecords(visible ? 1 : 0);
    if (visible) records.Get() = meshlet;
    records.OutputComplete();
}

[Shader("node")]
[NodeLaunch("thread")]
void EmitVisible(ThreadNodeInputRecord<TerrainMeshletRecord> input) {
    uint destination;
    terrainVisible.InterlockedAdd(0u, 1u, destination);
    if (destination >= terrainCapacity) {
        uint ignored;
        terrainVisible.InterlockedOr(4u, 1u, ignored);
        return;
    }
    TerrainMeshletRecord meshlet = input.Get();
    terrainVisible.Store2(32u + terrainSectionCount * 32u + destination * 8u,
        uint2(meshlet.descriptor, meshlet.section));
}
