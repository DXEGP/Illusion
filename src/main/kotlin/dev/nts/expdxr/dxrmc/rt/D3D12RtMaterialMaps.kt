package dev.nts.expdxr.dxrmc.rt

import com.mojang.blaze3d.platform.NativeImage
import dev.nts.expdxr.misc.IllusionLogger
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * labPBR 材质图接入（见 `docs/rt/README.md` §17）：按精灵名找 `_s` / `_n`，解码成材质表要的数值。
 *
 * 两个来源：`[materials] packPath` 指向的目录（开发期直连材质包仓库，发行版不带），或游戏里
 * 启用的资源包（玩家自己启用的 PBR 材质包）。两者都取不到时必须返回空的 [Look] 退化：材质表
 * 保留内置启发式，绝不能让「没有材质包」变成报错或全黑。
 */
internal object D3D12RtMaterialMaps {
    /** 一张精灵的 labPBR 参数；`roughness` = 1 - 光滑度（R），`metalness` = F0 通道（G）。 */
    class Maps(val roughness: Float, val metalness: Float)

    /** 一次查询的结果；`spec` 为空表示没有 `_s`，`normal` 表示旁边还有一张 `_n`。 */
    class Look(val spec: Maps?, val normal: Boolean)

    private val NONE = Look(null, false)

    private val cache = ConcurrentHashMap<String, Look>()

    private var specHits = 0
    private var normalHits = 0
    private var looked = 0
    private var source = "off"

    /** 每次重烘前清空：图集换了就要重新解析一遍，缓存里的旧值不能再信。 */
    fun beginBake() {
        cache.clear()
        specHits = 0
        normalHits = 0
        looked = 0
        source = when {
            !D3D12Settings.materialsEnabled -> "off"
            D3D12Settings.materialsPackPath.isNotEmpty() -> "packPath"
            else -> "resources"
        }
    }

    /** 这次重烘的命中情况，一行；`_n` 目前只探测不解码（§17.3 才接法线扰动）。 */
    fun summary(): String {
        val text = "rt materials: spec=$specHits normal=$normalHits of $looked from $source"
        IllusionLogger.infoMessage(text)
        return text
    }

    /**
     * 查一张精灵的材质图；`path` 是 `block/stone` 这种贴图路径（与图集精灵同名）。
     *
     * 没有映射时缓存空的 [Look]：一个世界几万个精灵，重复 IO 会把重烘拖成卡顿。
     */
    fun look(id: Identifier): Look {
        if (source == "off") return NONE
        val key = id.namespace + ":" + id.path
        cache[key]?.let { return it }
        looked++
        val spec = readMap(id, "_s")?.use { stream -> decode(stream)?.use { average(it) } }
        // 只关心有没有这张图：必须 use 一下，否则每张精灵漏一个文件句柄。
        val normal = readMap(id, "_n")?.use { true } ?: false
        if (spec != null) specHits++
        if (normal) normalHits++
        val result = Look(spec, normal)
        cache[key] = result
        return result
    }

    /** 取一张图；返回的流由调用方关闭。找不到就是 `null`，不抛。 */
    private fun readMap(id: Identifier, suffix: String): InputStream? = runCatching {
        val packPath = D3D12Settings.materialsPackPath
        if (packPath.isNotEmpty()) {
            return@runCatching readFromDirectory(packPath, id, suffix)
        }
        val location = Identifier.fromNamespaceAndPath(id.namespace, "textures/" + id.path + suffix + ".png")
        val resource = Minecraft.getInstance().resourceManager.getResource(location)
        // Optional 为空说明这一层资源包没提供这张图：正常情况，不当异常。
        if (resource.isPresent) resource.get().open() else null
    }.getOrNull()

    private fun readFromDirectory(packPath: String, id: Identifier, suffix: String): InputStream? {
        val root = Path.of(packPath)
        val base = if (root.isAbsolute) root else Minecraft.getInstance().gameDirectory.toPath().resolve(root)
        val file = base.resolve("assets").resolve(id.namespace).resolve("textures")
            .resolve(id.path + suffix + ".png")
        return if (Files.isRegularFile(file)) Files.newInputStream(file) else null
    }

    private fun decode(stream: InputStream): NativeImage? =
        runCatching { NativeImage.read(stream) }.getOrNull()

    /**
     * `_s` 的通道均值：R 光滑度、G 的 F0/金属、B 孔隙（孔隙还没接）。
     *
     * `NativeImage` 的像素是 ABGR 打包（A 在最高字节），与直觉的 ARGB 相反，取通道别取反。
     */
    private fun average(image: NativeImage): Maps? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        var red = 0.0
        var green = 0.0
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = image.getPixel(x, y)
                if ((pixel ushr 24) and 0xFF == 0) continue
                red += (pixel and 0xFF) / 255.0
                green += ((pixel ushr 8) and 0xFF) / 255.0
                count++
            }
        }
        if (count == 0) return null
        return Maps(
            roughness = (1.0 - red / count).toFloat().coerceIn(0f, 1f),
            metalness = (green / count).toFloat().coerceIn(0f, 1f),
        )
    }
}
