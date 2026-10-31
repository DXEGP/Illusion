package dev.nts.expdxr.dxrmc.rt

/**
 * B-Mega 主光线遍历（S2 2-d）的入口描述。
 * 这里只保留「建管线要用的数」——入口名与 SRV/UAV 槽数，二者必须与 HLSL 里的绑定顺序一致，。
 * 见 docs/rt/README.md
 */
object D3D12RtTraceShader {
    /** 入口名，`D3D12CompileShaderDxc` 按它编译。 */
    const val ENTRY: String = "MegaTraceMain"

    /** t0..t4：段记录 / 顶点 / 索引 / 材质表 / 逐 quad 材质。 */
    const val SRV_COUNT: Int = 5

    /** 加速结构变体多一个 t5：TLAS（S3 3-d）。 */
    const val SRV_COUNT_AS: Int = 6

    /** u0..u2：直接光 / 一次弹射 / 辅助（命中距离与法线）。 */
    const val UAV_COUNT: Int = 3

    /**
     * `WgaFrame.g_misc.y`（`debugMode`）里的探针档：着色器把常量本身回读进输出缓冲，
     * 用来证明"常量真的到了着色器"。与 `mega_trace.hlsl` 里的 `RtDebugMode() == 8u` 成对，
     * `_port/rt_shader_files_check.py` 会对拍两侧。
     */
    const val DEBUG_MODE_PROBE: Int = 8

    /**
     * 自检环境的天空档：着色器把天空换成常量 `g_moonRadiance.rgb`（即场景的 `skyColor`），
     * CPU 参考才对得上。正常出图用 `0`，天空走光影包的解析大气。
     */
    const val DEBUG_MODE_SELF_TEST: Int = 9

    private const val SOURCE = "mega_trace.hlsl"

    /** 装配完整 HLSL（`#include` 已展开）。`asPath` 时在文件头前置 `#define RT_AS 1`。 */
    fun build(asPath: Boolean = false): String {
        val text = D3D12RtShaderSources.load(D3D12ShaderPacks.sourcePath(SOURCE))
        return if (asPath) "#define RT_AS 1\n$text" else text
    }
}
