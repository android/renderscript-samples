package com.android.example.rsmigration

import android.annotation.SuppressLint
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.RuntimeException
import kotlin.math.cos
import kotlin.math.sin

@RequiresApi(Build.VERSION_CODES.S)
class RenderEffectImageProcessor : ImageProcessor {
    override val name = "RenderEffect"
    private var params: Params? = null

    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        params = Params(
            inputImage,
            numberOfOutputImages
        )
    }

    private fun applyEffect(it: Params, renderEffect: RenderEffect, outputIndex: Int): Bitmap {
        it.renderNode.setRenderEffect(renderEffect)
        val renderCanvas = it.renderNode.beginRecording()
        renderCanvas.drawBitmap(it.bitmap, 0f, 0f, null)
        it.renderNode.endRecording()
        it.hardwareRenderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()

        val image = it.imageReader.acquireNextImage() ?: throw RuntimeException("No Image")
        val hardwareBuffer = image.hardwareBuffer ?: throw RuntimeException("No HardwareBuffer")
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            ?: throw RuntimeException("Create Bitmap Failed")
        hardwareBuffer.close()
        image.close()
        return bitmap
    }

    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        params?.let {
            val colorMatrix = if (radian == 0f) {
                ColorMatrix()
            } else {
                val cos = cos(radian.toDouble())
                val sin = sin(radian.toDouble())
                ColorMatrix(floatArrayOf(
                    (.299 + .701 * cos + .168 * sin).toFloat(), //0
                    (.587 - .587 * cos + .330 * sin).toFloat(), //1
                    (.114 - .114 * cos - .497 * sin).toFloat(), //2
                    0f, 0f,                                     //3,4
                    (.299 - .299 * cos - .328 * sin).toFloat(), //5
                    (.587 + .413 * cos + .035 * sin).toFloat(), //6
                    (.114 - .114 * cos + .292 * sin).toFloat(), //7
                    0f, 0f,                                     //8,9
                    (.299 - .300 * cos + 1.25 * sin).toFloat(), //10
                    (.587 - .588 * cos - 1.05 * sin).toFloat(), //11
                    (.114 + .886 * cos - .203 * sin).toFloat(), //12
                    0f, 0f, 0f, 0f, 0f,                         //13,14,15,16,17
                    1f, 0f                                      //18,19
                ))
            }
            val colorFilterEffect =
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
            return applyEffect(it, colorFilterEffect, outputIndex)
        }
        throw RuntimeException("Not configured!")
    }

    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        params?.let {
            val blurRenderEffect = RenderEffect.createBlurEffect(
                radius, radius,
                Shader.TileMode.MIRROR
            )
            return applyEffect(it, blurRenderEffect, outputIndex)
        }
        throw RuntimeException("Not configured!")
    }

    override fun cleanup() {
        params?.let {
            params = null
            it.imageReader.close()
            it.renderNode.discardDisplayList()
            it.hardwareRenderer.destroy()
        }
    }

    inner class Params(val bitmap: Bitmap, numberOfOutputImages: Int) {
        @SuppressLint("WrongConstant")
        val imageReader = ImageReader.newInstance(
            bitmap.width, bitmap.height,
            PixelFormat.RGBA_8888, numberOfOutputImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderNode = RenderNode("RenderEffect")
        val hardwareRenderer = HardwareRenderer()

        init {
            hardwareRenderer.setSurface(imageReader.surface)
            hardwareRenderer.setContentRoot(renderNode)
            renderNode.setPosition(0, 0, imageReader.width, imageReader.height)
        }
    }
}