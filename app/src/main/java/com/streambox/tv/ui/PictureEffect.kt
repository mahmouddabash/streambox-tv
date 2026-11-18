package com.streambox.tv.ui

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Real-time picture enhancement on the GPU: unsharp-mask sharpening, contrast, brightness,
 * saturation and "vibrance" (boosts dull colours more than already-vivid ones, so skin stays natural).
 */
@OptIn(markerClass = [UnstableApi::class])
class PictureEffect(private val mode: PictureMode) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(mode, useHdr)

    private class Program(private val mode: PictureMode, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var texelX = 0f
        private var texelY = 0f

        init {
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelX = 1f / inputWidth.coerceAtLeast(1)
            texelY = 1f / inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform("uTexel", floatArrayOf(texelX, texelY))
                glProgram.setFloatUniform("uSharpen", mode.sharpen)
                glProgram.setFloatUniform("uContrast", mode.contrast)
                glProgram.setFloatUniform("uBrightness", mode.brightness)
                glProgram.setFloatUniform("uSaturation", mode.saturation)
                glProgram.setFloatUniform("uVibrance", mode.vibrance)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexel;
            uniform float uSharpen;
            uniform float uContrast;
            uniform float uBrightness;
            uniform float uSaturation;
            uniform float uVibrance;
            varying vec2 vTexSamplingCoord;

            void main() {
              vec2 uv = vTexSamplingCoord;
              vec4 src = texture2D(uTexSampler, uv);
              vec3 c = src.rgb;

              // Unsharp mask: add back the difference to a small cross blur.
              vec3 blur = (texture2D(uTexSampler, uv + vec2(uTexel.x, 0.0)).rgb +
                           texture2D(uTexSampler, uv - vec2(uTexel.x, 0.0)).rgb +
                           texture2D(uTexSampler, uv + vec2(0.0, uTexel.y)).rgb +
                           texture2D(uTexSampler, uv - vec2(0.0, uTexel.y)).rgb) * 0.25;
              vec3 detail = c - blur;
              c += clamp(detail * uSharpen, -0.12, 0.12);

              // Contrast around mid-grey, then brightness.
              c = (c - 0.5) * uContrast + 0.5 + uBrightness;

              // Saturation + vibrance (less boost for colours that are already saturated).
              float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
              float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
              float s = uSaturation * (1.0 + uVibrance * (1.0 - clamp(sat, 0.0, 1.0)));
              c = mix(vec3(luma), c, s);

              gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
            }
        """
    }
}

/** Picture presets shown in the player menu and Settings. */
enum class PictureMode(
    val id: String,
    val label: String,
    val sharpen: Float,
    val contrast: Float,
    val brightness: Float,
    val saturation: Float,
    val vibrance: Float
) {
    OFF("off", "Original", 0f, 1f, 0f, 1f, 0f),
    ENHANCED("enhanced", "Enhanced (sharper, richer colour)", 0.55f, 1.06f, 0.0f, 1.10f, 0.25f),
    VIVID("vivid", "Vivid (bright and punchy)", 0.75f, 1.12f, 0.015f, 1.22f, 0.35f),
    CINEMA("cinema", "Cinema (deep, natural)", 0.40f, 1.10f, -0.02f, 1.04f, 0.15f),
    SHARP("sharp", "Sharp (maximum detail)", 1.10f, 1.04f, 0f, 1.06f, 0.1f);

    companion object {
        fun of(id: String): PictureMode = values().firstOrNull { it.id == id } ?: ENHANCED
    }
}
