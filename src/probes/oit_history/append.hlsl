[numthreads(64,1,1)]
void AppendPackedRecords(uint3 thread : SV_DispatchThreadID) {
    McdrOitFragmentV1 fragment = (McdrOitFragmentV1)0;
    fragment.position = float4(0.5f, 0.5f, 0.5f, 1.0f);
    fragment.finalQ = float4(0.125f, 0.125f, 0.125f, 0.5f);
    fragment.materialRgb = 0.25f.xxx;
    fragment.materialAlpha = 1.0f;
    fragment.sampleAlpha = 0.5f;
    fragment.transmittance = 0.5f;
    fragment.accumAlpha = 0.5f;
    fragment.fogFactor = 0.25f;
    fragment.chunkVisibility = 0.75f;
    fragment.coverage = 1.0f;
    fragment.primitiveIndex = thread.x % 4u == 0u ? 0xffffffffu : 0u;
    fragment.validity = MCDR_OIT_CURRENT_MATERIAL_VALID | MCDR_OIT_COVERAGE_VALID;
    AppendV1(fragment);
}
