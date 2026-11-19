cbuffer McdrTileBinBatchV1 : register(b2, space3) {
    uint McdrTilePrimitiveCount;
    uint McdrTileRefCapacity;
    uint McdrTileCommandKind;
    uint McdrTileCommandStride;
#ifdef MCDR_TILE_TERRAIN_ENVELOPES
    uint McdrTileFirstVisible;
#endif
};

#ifndef MCDR_TILE_TERRAIN_ENVELOPES
StructuredBuffer<McdrTileEnvelopeV1> McdrTileEnvelopesV1 : register(t8, space3);
RWByteAddressBuffer McdrTileMasksV1 : register(u0, space3);
RWByteAddressBuffer McdrTilePrefixesV1 : register(u1, space3);
RWByteAddressBuffer McdrTileCommandsV1 : register(u2, space3);
RWByteAddressBuffer McdrTileControlV1 : register(u3, space3);

struct McdrTileBinEntryV1 {
    uint3 grid : SV_DispatchGrid;
    uint primitiveCount;
};

struct McdrTileBinPrimitiveV1 {
    uint primitive;
};

bool McdrTileBinBatchValidV1() {
    return McdrTileSchemaVersion == 1u && McdrTileFlags == 0u &&
        McdrTileResidentCount > 0u && McdrTileResidentCount <= 16u &&
        McdrTilePrimitiveCount <= 4096u &&
        McdrTileRefCapacity >= McdrTilePrimitiveCount * McdrTileResidentCount &&
        ((McdrTileCommandKind == 0u && McdrTileCommandStride == 36u) ||
         (McdrTileCommandKind == 1u && McdrTileCommandStride == 28u));
}

#ifdef MCDR_TILE_BIN_NODES
[Shader("node")]
[NodeLaunch("broadcasting")]
[NodeIsProgramEntry]
[NodeMaxDispatchGrid(64, 1, 1)]
[NumThreads(64, 1, 1)]
void TileEnvelopeEntryV1(DispatchNodeInputRecord<McdrTileBinEntryV1> input,
    uint dispatchThread : SV_DispatchThreadID,
    [MaxRecords(64)] [NodeID("TileEnvelopeClassifyV1")] NodeOutput<McdrTileBinPrimitiveV1> output) {
    bool batchValid = McdrTileBinBatchValidV1() && input.Get().primitiveCount == McdrTilePrimitiveCount &&
        all(input.Get().grid == uint3((McdrTilePrimitiveCount + 63u) / 64u, 1u, 1u));
    if (!batchValid && dispatchThread == 0u) {
        uint ignored;
        McdrTileControlV1.InterlockedOr(4u, 2u, ignored);
    }
    bool valid = batchValid && dispatchThread < McdrTilePrimitiveCount;
    ThreadNodeOutputRecords<McdrTileBinPrimitiveV1> records = output.GetThreadNodeOutputRecords(valid ? 1 : 0);
    if (valid) records.Get().primitive = dispatchThread;
    records.OutputComplete();
}

[Shader("node")]
[NodeLaunch("thread")]
void TileEnvelopeClassifyV1(ThreadNodeInputRecord<McdrTileBinPrimitiveV1> input) {
    uint primitive = input.Get().primitive;
    McdrTileEnvelopeV1 envelope = McdrTileEnvelopesV1[primitive];
    bool valid = envelope.identity.w == McdrTileCommandKind &&
        (envelope.properties.x & ~1u) == 0u && all(envelope.properties.yzw == 0u) &&
        (McdrTileCommandKind != 0u || (envelope.draw.x == 3u && envelope.draw.w == 0u));
    bool allTiles = (envelope.properties.x & 1u) != 0u || !all(isfinite(envelope.pixelBounds));
    uint mask = 0u;
    for (uint tileSlot = 0u; tileSlot < McdrTileResidentCount; ++tileSlot) {
        uint4 tile = McdrTileRegions[tileSlot];
        bool regionValid = all(tile.zw > 0u) && all(tile.zw <= 64u) && all((tile.xy & 1u) == 0u);
        valid = valid && regionValid;
        float2 minimum = float2(tile.xy) - 2.25;
        float2 maximum = float2(tile.xy + tile.zw) + 2.25;
        bool overlaps = all(envelope.pixelBounds.zw >= minimum) && all(envelope.pixelBounds.xy <= maximum);
        if (allTiles || overlaps) mask |= 1u << tileSlot;
    }
    if (!valid) {
        uint ignored;
        McdrTileControlV1.InterlockedOr(4u, 4u, ignored);
        mask = 0u;
    }
    McdrTileMasksV1.Store(primitive * 4u, mask);
}

#else

groupshared uint McdrTilePrefixLaneTotalsV1[1024];
groupshared uint McdrTilePrefixTotalV1;

[numthreads(1, 1, 1)]
void InitializeTileBinsV1(uint3 thread : SV_DispatchThreadID) {
    McdrTileControlV1.Store4(0u, uint4(0u, McdrTileBinBatchValidV1() ? 0u : 1u, 0u, 0u));
}

[numthreads(1024, 1, 1)]
void PrefixTileRefsV1(uint3 thread : SV_DispatchThreadID) {
    uint lane = thread.x;
    uint firstPrimitive = lane * 4u;
    bool batchValid = McdrTileBinBatchValidV1();
    bool hasError = McdrTileControlV1.Load(4u) != 0u;
    uint localPrefix[4];
    uint localCount = 0u;
    [unroll]
    for (uint item = 0u; item < 4u; ++item) {
        uint primitive = firstPrimitive + item;
        localPrefix[item] = localCount;
        uint itemCount = batchValid && !hasError && primitive < McdrTilePrimitiveCount
            ? countbits(McdrTileMasksV1.Load(primitive * 4u))
            : 0u;
        localCount += itemCount;
    }
    McdrTilePrefixLaneTotalsV1[lane] = localCount;

    GroupMemoryBarrierWithGroupSync();

    for (uint upStride = 1u; upStride < 1024u; upStride <<= 1u) {
        uint index = (lane + 1u) * (upStride << 1u) - 1u;
        if (index < 1024u) McdrTilePrefixLaneTotalsV1[index] += McdrTilePrefixLaneTotalsV1[index - upStride];
        GroupMemoryBarrierWithGroupSync();
    }

    if (lane == 0u) {
        McdrTilePrefixTotalV1 = McdrTilePrefixLaneTotalsV1[1023u];
        McdrTilePrefixLaneTotalsV1[1023u] = 0u;
    }
    GroupMemoryBarrierWithGroupSync();

    for (uint downStride = 512u; downStride != 0u; downStride >>= 1u) {
        uint index = (lane + 1u) * (downStride << 1u) - 1u;
        if (index < 1024u) {
            uint left = McdrTilePrefixLaneTotalsV1[index - downStride];
            McdrTilePrefixLaneTotalsV1[index - downStride] = McdrTilePrefixLaneTotalsV1[index];
            McdrTilePrefixLaneTotalsV1[index] += left;
        }
        GroupMemoryBarrierWithGroupSync();
    }

    [unroll]
    for (uint outputItem = 0u; outputItem < 4u; ++outputItem) {
        uint primitive = firstPrimitive + outputItem;
        if (batchValid && !hasError && primitive < McdrTilePrimitiveCount) {
            McdrTilePrefixesV1.Store(primitive * 4u,
                McdrTilePrefixLaneTotalsV1[lane] + localPrefix[outputItem]);
        }
    }

    if (lane == 0u) {
        if (!batchValid || hasError) {
            McdrTileControlV1.Store(0u, 0u);
        } else if (McdrTilePrefixTotalV1 > McdrTileRefCapacity) {
            McdrTileControlV1.Store2(0u, uint2(0u, 8u));
        } else {
            McdrTileControlV1.Store(0u, McdrTilePrefixTotalV1);
        }
    }
}

[numthreads(64, 1, 1)]
void ScatterTileCommandsV1(uint3 thread : SV_DispatchThreadID) {
    uint primitive = thread.x;
    if (primitive >= McdrTilePrimitiveCount || McdrTileControlV1.Load(4u) != 0u) return;
    McdrTileEnvelopeV1 envelope = McdrTileEnvelopesV1[primitive];
    uint mask = McdrTileMasksV1.Load(primitive * 4u);
    uint destination = McdrTilePrefixesV1.Load(primitive * 4u);
    for (uint tileSlot = 0u; tileSlot < McdrTileResidentCount; ++tileSlot) {
        if ((mask & (1u << tileSlot)) == 0u) continue;
        uint address = destination * McdrTileCommandStride;
        McdrTileCommandsV1.Store4(address, uint4(tileSlot, envelope.identity.xyz));
        if (McdrTileCommandKind == 0u) {
            McdrTileCommandsV1.Store4(address + 16u, uint4(envelope.draw.x, 1u, envelope.draw.y, envelope.draw.z));
            McdrTileCommandsV1.Store(address + 32u, envelope.identity.y);
        } else {
            McdrTileCommandsV1.Store3(address + 16u, uint3(1u, 1u, 1u));
        }
        ++destination;
    }
}

#endif
#endif
