RWByteAddressBuffer McdrTerrainTileEnvelopesV1 : register(u4, space3);

static const float4 McdrTerrainClipPlanesV1[6] = {
    float4(0, 0, 1, 0), float4(0, 0, -1, 1),
    float4(1, 0, 0, 1), float4(-1, 0, 0, 1),
    float4(0, 1, 0, 1), float4(0, -1, 0, 1),
};

void McdrTileIncludeTerrainPixelV1(inout McdrTileEnvelopeV1 envelope, float4 clip) {
    if (clip.w <= 0.0) return;
    float2 ndc = clamp(clip.xy, -clip.w, clip.w) / clip.w;
    float2 pixel = McdrTileSourceViewport.xy + (ndc * float2(0.5, -0.5) + 0.5) * McdrTileSourceViewport.zw;
    envelope.pixelBounds.xy = min(envelope.pixelBounds.xy, pixel);
    envelope.pixelBounds.zw = max(envelope.pixelBounds.zw, pixel);
}

void McdrTileIncludeClippedTriangleV1(inout McdrTileEnvelopeV1 envelope, float4 first, float4 second, float4 third) {
    float4 polygon[9];
    polygon[0] = first;
    polygon[1] = second;
    polygon[2] = third;
    uint count = 3u;
    for (uint planeIndex = 0u; planeIndex < 6u && count > 0u; ++planeIndex) {
        float4 plane = McdrTerrainClipPlanesV1[planeIndex];
        float4 clipped[9];
        uint clippedCount = 0u;
        float4 previous = polygon[count - 1u];
        float previousDistance = dot(previous, plane);
        for (uint index = 0u; index < count; ++index) {
            float4 current = polygon[index];
            float distance = dot(current, plane);
            if ((previousDistance < 0.0 && distance > 0.0) || (previousDistance > 0.0 && distance < 0.0)) {
                float fraction = previousDistance / (previousDistance - distance);
                clipped[clippedCount++] = lerp(previous, current, fraction);
            }
            if (distance >= 0.0) clipped[clippedCount++] = current;
            previous = current;
            previousDistance = distance;
        }
        count = clippedCount;
        for (uint copy = 0u; copy < count; ++copy) polygon[copy] = clipped[copy];
    }
    for (uint index = 0u; index < count; ++index) McdrTileIncludeTerrainPixelV1(envelope, polygon[index]);
}

McdrTileEnvelopeV1 McdrBuildTerrainTileEnvelopeV1(uint localIndex) {
    McdrTileEnvelopeV1 envelope = McdrTileEmptyEnvelopeV1(uint4(0u, 0u, 0u, 1u), uint4(0u, 0u, 0u, 0u));
    uint4 header = gSections.Load4(0u);
    if (header.y != 0u || header.x > header.w || header.z != gMeshletGlobalsPad1 || header.w != gMeshletCount) {
        envelope.identity.w = 0u;
        return envelope;
    }
    if (McdrTileFirstVisible >= header.x || localIndex >= header.x - McdrTileFirstVisible) return envelope;

    uint2 work = loadMeshletWork(McdrTileFirstVisible + localIndex);
    if (work.y >= header.z) {
        envelope.identity.w = 0u;
        return envelope;
    }
    SectionDesc section = loadSection(work.y);
    if (work.x < section.meshletBase || work.x - section.meshletBase >= section.meshletCount) {
        envelope.identity.w = 0u;
        return envelope;
    }
    MeshletDesc meshlet = loadMeshletDescriptor(work.x, work.y);
    uint quadCount = meshlet.packedMin >> 24u;
    if (quadCount > MCDR_TILE_TERRAIN_MAX_QUADS || (meshlet.localVertexOffset & 3u) != 0u) {
        envelope.identity.w = 0u;
        return envelope;
    }
    envelope.identity.xyz = uint3(meshlet.localVertexOffset / 2u, work.y, work.x);

    for (uint quad = 0u; quad < quadCount; ++quad) {
        uint firstVertex = section.vertexBase + meshlet.localVertexOffset + quad * 4u;
        float4 clip[4];
        bool insideDepth = true;
        [unroll]
        for (uint corner = 0u; corner < 4u; ++corner) {
            clip[corner] = McdrTileEvaluateTerrainClipV1(section, firstVertex + corner);
            if (!all(isfinite(clip[corner]))) {
                envelope.identity.w = 0u;
                return envelope;
            }
            insideDepth = insideDepth && clip[corner].w > 0.0 &&
                clip[corner].z >= 0.0 && clip[corner].z <= clip[corner].w;
        }
        if (insideDepth) {
            [unroll]
            for (uint corner = 0u; corner < 4u; ++corner) McdrTileIncludeTerrainPixelV1(envelope, clip[corner]);
        } else {
            McdrTileIncludeClippedTriangleV1(envelope, clip[0], clip[1], clip[2]);
            McdrTileIncludeClippedTriangleV1(envelope, clip[0], clip[2], clip[3]);
        }
    }
    return envelope;
}

[numthreads(64, 1, 1)]
void BuildTerrainTileEnvelopesV1(uint3 thread : SV_DispatchThreadID) {
    uint localIndex = thread.x;
    if (localIndex >= McdrTilePrimitiveCount) return;
    McdrTileEnvelopeV1 envelope = McdrBuildTerrainTileEnvelopeV1(localIndex);
    uint address = localIndex * 64u;
    McdrTerrainTileEnvelopesV1.Store4(address, asuint(envelope.pixelBounds));
    McdrTerrainTileEnvelopesV1.Store4(address + 16u, envelope.identity);
    McdrTerrainTileEnvelopesV1.Store4(address + 32u, envelope.draw);
    McdrTerrainTileEnvelopesV1.Store4(address + 48u, envelope.properties);
}
