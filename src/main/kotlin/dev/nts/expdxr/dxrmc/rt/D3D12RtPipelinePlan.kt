package dev.nts.expdxr.dxrmc.rt

import dev.nts.expdxr.RtDenoise
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings

/**
 * 光追管线的唯一真值表：分辨率、要不要降噪、要不要超分，全从 [configured] 一处算出来。
 *
 * 谁要分辨率就调 [configured]，不要各自拿显示分辨率再乘一次——分母对不齐的表现是
 * 「历史读错区、画面边缘糊成一圈」，不报错、也不在日志里。见 docs/rt/README.md §18。
 */
internal object D3D12RtPipelinePlan {
    private const val MIN_SIDE = 16

    /** 一帧的形状：谁在什么分辨率上跑、后面还要不要再过一道降噪与超分。 */
    class Plan(
        val track: RtDenoise,
        val displayWidth: Int,
        val displayHeight: Int,
        val renderWidth: Int,
        val renderHeight: Int,
        val gbufferWidth: Int,
        val gbufferHeight: Int,
        val denoise: Boolean,
        val superResolution: Boolean,
    )

    private var lastLine: String? = null

    /** 按当前配置与显示分辨率算出这一帧的形状；分辨率下限 [MIN_SIDE]，避免缩到 0 让派发退化。 */
    fun configured(displayWidth: Int, displayHeight: Int): Plan {
        val track = D3D12Settings.rtDenoise
        val scale = D3D12Settings.rtRenderScale.coerceIn(25, 100)
        // 光追整条不跑时倍率没有意义，按 100% 算，别让它去缩一个根本不存在的渲染目标。
        val renderScale = if (track == RtDenoise.OFF) 100 else scale
        val renderWidth = side(displayWidth, renderScale)
        val renderHeight = side(displayHeight, renderScale)
        val denoise = track == RtDenoise.RR || track == RtDenoise.NRD_SR
        val superResolution = track == RtDenoise.RR || track == RtDenoise.NRD_SR
        // RR 要求 G-Buffer 与渲染分辨率一致；解耦轨也一样，只有关闭光追时才没有 G-Buffer。
        val gbufferWidth = if (track == RtDenoise.OFF) 0 else renderWidth
        val gbufferHeight = if (track == RtDenoise.OFF) 0 else renderHeight
        return Plan(
            track = track,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            renderWidth = renderWidth,
            renderHeight = renderHeight,
            gbufferWidth = gbufferWidth,
            gbufferHeight = gbufferHeight,
            denoise = denoise,
            superResolution = superResolution,
        )
    }

    /** 表变了才打一行：分辨率、轨道、G-Buffer 尺寸都能一眼对上。 */
    fun note(plan: Plan) {
        val line = describe(plan)
        if (line == lastLine) return
        lastLine = line
        IllusionLogger.infoMessage(line)
    }

    fun describe(plan: Plan): String = "rt: denoise=${plan.track.name.lowercase()} " +
        "render=${plan.renderWidth}x${plan.renderHeight} " +
        "gbuffer=${plan.gbufferWidth}x${plan.gbufferHeight}"

    private fun side(display: Int, percent: Int): Int {
        val scaled = display * percent / 100
        return scaled.coerceAtLeast(MIN_SIDE)
    }
}
