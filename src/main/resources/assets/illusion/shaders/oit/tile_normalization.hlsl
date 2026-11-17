Texture2DArray<float4> McdrOitQ : register(t0);
Texture2DArray<float4> McdrOitCoeff0 : register(t1);
#if MCDR_OIT_TARGET_COUNT > 1
Texture2DArray<float4> McdrOitCoeff1 : register(t2);
#endif
#if MCDR_OIT_TARGET_COUNT > 2
Texture2DArray<float4> McdrOitCoeff2 : register(t3);
Texture2DArray<float4> McdrOitCoeff3 : register(t4);
#endif
RWTexture2DArray<float4> McdrOitNormalized : register(u0);

cbuffer McdrOitNormalizationConstants : register(b0) {
    uint McdrOitSchema;
    uint McdrOitResidentCount;
    uint McdrOitLane;
    uint McdrOitFlags;
    uint2 McdrOitOutputExtent;
    uint2 McdrOitReserved;
    uint4 McdrOitRegions[16];
};

static const float MCDR_OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE = 0.02;
static const float MCDR_OIT_EPSILON = 0.00001;

float toAbsorbance(float transmittance) {
    return clamp(-log(max(transmittance, 0.0001)), 0.0, 4.0);
}

float toTransmittance(float absorbance) {
    return clamp(exp(-absorbance), 0.0001, 1.0);
}

float sampleAbsorbance(float coefficients[MCDR_OIT_TARGET_COUNT * 4], float originalDepth,
    float currentAbsorbance) {
    const int coefficientCount = MCDR_OIT_TARGET_COUNT * 4;
    const int depthBins = coefficientCount;
    float averageAbsorbance = coefficients[0];
    if (averageAbsorbance == 0.0) return 0.0;
    float depthMeasuredInBins = originalDepth * (depthBins - 1);
    float depth = depthMeasuredInBins / depthBins;
    averageAbsorbance -= currentAbsorbance * (1.0 - depth);
    int treeIndexB = clamp((int)floor(depthMeasuredInBins), 0, depthBins - 1);
    bool shouldSampleA = treeIndexB >= 1;
    int treeIndexA = shouldSampleA ? treeIndexB - 1 : treeIndexB;
    treeIndexB += coefficientCount;
    treeIndexA += coefficientCount;
    float sampleB = averageAbsorbance;
    float sampleA = shouldSampleA ? averageAbsorbance : 0.0;
    const int waveletRank = firstbitlow((uint)coefficientCount) - 1;
    for (int waveletLevel = 0; waveletLevel <= waveletRank; ++waveletLevel) {
        int power = waveletRank - waveletLevel;
        float waveletWidth = exp2(-power);
        float waveletHeightScale = exp2(power * 0.5);
        int parentIndexB = treeIndexB >> 1;
        int isRightHalfB = treeIndexB & 1;
        int waveletSignB = 1 - (isRightHalfB << 1);
        float indexAtParentLevel = (float)(parentIndexB & ((1u << power) - 1u));
        float depthOffsetRelativeToWavelet = depth - waveletWidth * indexAtParentLevel;
        float currentDifferenceCoefficient = (isRightHalfB * waveletWidth +
            waveletSignB * depthOffsetRelativeToWavelet) * waveletHeightScale * currentAbsorbance;
        float differenceCoefficientB = coefficients[parentIndexB] - currentDifferenceCoefficient;
        sampleB -= waveletHeightScale * differenceCoefficientB * waveletSignB;
        treeIndexB = parentIndexB;
        if (shouldSampleA) {
            int parentIndexA = treeIndexA >> 1;
            float differenceCoefficientA = parentIndexA == parentIndexB
                ? differenceCoefficientB : coefficients[parentIndexA];
            int isRightHalfA = treeIndexA & 1;
            int waveletSignA = 1 - (isRightHalfA << 1);
            sampleA -= waveletHeightScale * differenceCoefficientA * waveletSignA;
            treeIndexA = parentIndexA;
        }
    }
    float lerpAlpha = depthMeasuredInBins >= depthBins ? 1.0 : frac(depthMeasuredInBins);
    return lerp(sampleA, sampleB, lerpAlpha);
}

float sampleTransmittance(uint2 position, uint tileSlot) {
    float coefficients[MCDR_OIT_TARGET_COUNT * 4];
    float4 coefficientSamples[MCDR_OIT_TARGET_COUNT];
    coefficientSamples[0] = McdrOitCoeff0.Load(int4(position, tileSlot, 0));
#if MCDR_OIT_TARGET_COUNT > 1
    coefficientSamples[1] = McdrOitCoeff1.Load(int4(position, tileSlot, 0));
#endif
#if MCDR_OIT_TARGET_COUNT > 2
    coefficientSamples[2] = McdrOitCoeff2.Load(int4(position, tileSlot, 0));
    coefficientSamples[3] = McdrOitCoeff3.Load(int4(position, tileSlot, 0));
#endif
    for (uint target = 0; target < MCDR_OIT_TARGET_COUNT; ++target)
        for (uint component = 0; component < 4; ++component)
            coefficients[target * 4 + component] = coefficientSamples[target][component];
    return toTransmittance(sampleAbsorbance(coefficients, 100000.0, toAbsorbance(1.0)));
}

[numthreads(8, 8, 1)]
void NormalizeOitLane(uint3 dispatchThread : SV_DispatchThreadID) {
    if (McdrOitSchema != 1u || McdrOitFlags != 0u || McdrOitResidentCount > 16u ||
        dispatchThread.z >= McdrOitResidentCount || McdrOitLane >= 4u ||
        any(dispatchThread.xy >= 68u)) return;
    uint2 position = dispatchThread.xy;
    uint outputSlice = McdrOitLane * McdrOitResidentCount + dispatchThread.z;
    float4 accumulatedColor = McdrOitQ.Load(int4(position, dispatchThread.z, 0));
    float sampledTransmittance = sampleTransmittance(position, dispatchThread.z);
    float coverage = 1.0 - (sampledTransmittance < MCDR_OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE
        ? 0.0 : sampledTransmittance);
    if (coverage < MCDR_OIT_EPSILON && dot(accumulatedColor.rgb, 1.0.xxx) < MCDR_OIT_EPSILON) {
        McdrOitNormalized[uint3(position, outputSlice)] = 0.0.xxxx;
        return;
    }
    float normalization = accumulatedColor.a > MCDR_OIT_EPSILON
        ? coverage / accumulatedColor.a : 1.0;
    McdrOitNormalized[uint3(position, outputSlice)] =
        float4(accumulatedColor.rgb * normalization, coverage);
}

Texture2DArray<float4> McdrOitNormalizedInput : register(t5);
RWTexture2D<float4> McdrOitCorrected : register(u1);

[numthreads(8, 8, 1)]
void AverageNormalizedOitLanes(uint3 dispatchThread : SV_DispatchThreadID) {
    if (McdrOitSchema != 1u || McdrOitFlags != 0u || McdrOitResidentCount > 16u ||
        dispatchThread.z >= McdrOitResidentCount) return;
    uint4 region = McdrOitRegions[dispatchThread.z];
    if (any(dispatchThread.xy >= region.zw)) return;
    uint2 outputPosition = region.xy + dispatchThread.xy;
    if (any(outputPosition >= McdrOitOutputExtent)) return;
    uint2 position = dispatchThread.xy + 2u;
    float4 value = 0.0.xxxx;
    [unroll]
    for (uint lane = 0; lane < 4u; ++lane)
        value += McdrOitNormalizedInput.Load(int4(position,
            lane * McdrOitResidentCount + dispatchThread.z, 0));
    McdrOitCorrected[outputPosition] = value * 0.25;
}
