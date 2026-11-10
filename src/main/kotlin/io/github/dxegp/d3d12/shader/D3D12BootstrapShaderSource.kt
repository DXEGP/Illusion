package io.github.dxegp.d3d12.shader

import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import net.minecraft.resources.Identifier

internal object D3D12BootstrapShaderSource {
    private val CORE_GUI_VSH = """
        #version 330

        // Can't moj_import in things used during startup, when resource packs don't exist.
        // This is a copy of dynamicimports.glsl and projection.glsl
        layout(std140) uniform DynamicTransforms {
            mat4 ModelViewMat;
            vec4 ColorModulator;
            vec3 ModelOffset;
            mat4 TextureMat;
        };
        layout(std140) uniform Projection {
            mat4 ProjMat;
        };

        in vec3 Position;
        in vec4 Color;

        out vec4 vertexColor;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

            vertexColor = Color;
        }
        """.trimIndent()

    private val CORE_GUI_FSH = """
        #version 330

        // Can't moj_import in things used during startup, when resource packs don't exist.
        // This is a copy of dynamicimports.glsl
        layout(std140) uniform DynamicTransforms {
            mat4 ModelViewMat;
            vec4 ColorModulator;
            vec3 ModelOffset;
            mat4 TextureMat;
        };

        in vec4 vertexColor;

        out vec4 fragColor;

        void main() {
            vec4 color = vertexColor;
            if (color.a == 0.0) {
                discard;
            }
            fragColor = color * ColorModulator;
        }
        """.trimIndent()

    private val CORE_POSITION_TEX_COLOR_VSH = """
        #version 330

        // Can't moj_import in things used during startup, when resource packs don't exist.
        // This is a copy of dynamicimports.glsl and projection.glsl
        layout(std140) uniform DynamicTransforms {
            mat4 ModelViewMat;
            vec4 ColorModulator;
            vec3 ModelOffset;
            mat4 TextureMat;
        };
        layout(std140) uniform Projection {
            mat4 ProjMat;
        };

        in vec3 Position;
        in vec2 UV0;
        in vec4 Color;

        out vec2 texCoord0;
        out vec4 vertexColor;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

            texCoord0 = UV0;
            vertexColor = Color;
        }
        """.trimIndent()

    private val CORE_POSITION_TEX_COLOR_FSH = """
        #version 330

        // Can't moj_import in things used during startup, when resource packs don't exist.
        // This is a copy of dynamicimports.glsl
        layout(std140) uniform DynamicTransforms {
            mat4 ModelViewMat;
            vec4 ColorModulator;
            vec3 ModelOffset;
            mat4 TextureMat;
        };

        uniform sampler2D Sampler0;

        in vec2 texCoord0;
        in vec4 vertexColor;

        out vec4 fragColor;

        void main() {
            vec4 color = texture(Sampler0, texCoord0) * vertexColor;
            if (color.a == 0.0) {
                discard;
            }
            fragColor = color * ColorModulator;
        }
        """.trimIndent()

    val shaderSource: ShaderSource = object : ShaderSource {
        override fun getShader(id: Identifier, type: ShaderType): String? {
            if (!id.path.startsWith("core/")) {
                return null
            }
            return when (id.path) {
                "core/gui" -> if (type == ShaderType.VERTEX) CORE_GUI_VSH else CORE_GUI_FSH
                "core/position_tex_color" -> if (type == ShaderType.VERTEX) {
                    CORE_POSITION_TEX_COLOR_VSH
                } else {
                    CORE_POSITION_TEX_COLOR_FSH
                }
                else -> null
            }
        }

        override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = null

        override fun close() {}
    }

}
