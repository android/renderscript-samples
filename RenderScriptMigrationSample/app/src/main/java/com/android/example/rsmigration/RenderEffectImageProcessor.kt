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
class RenderEffectImageProcessor:ImageProcessor {
    override val name = "RenderEffect"
    private var params:Params? = null

    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        params = Params(
            inputImage,
            numberOfOutputImages
        )
    }

    private fun applyEffect(it:Params, renderEffect: RenderEffect, outputIndex: Int):Bitmap {
        it.renderNode.setRenderEffect(renderEffect)
        //This isn't necessary, but cleans up the logs
        it.hardwareBuffers[outputIndex]?.close()
        val renderCanvas = it.renderNode.beginRecording()
        renderCanvas.drawBitmap(it.bitmap, 0f,0f, null)
        it.renderNode.endRecording()
        it.hardwareRenderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()

        val image = it.imageReader.acquireNextImage() ?: throw RuntimeException("No Image")
        val hardwareBuffer = image.hardwareBuffer ?: throw RuntimeException("No HardwareBuffer")
        it.hardwareBuffers[outputIndex] = hardwareBuffer
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: throw RuntimeException("Create Bitmap Failed")
        image.close()
        return bitmap
    }

    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        params?.let {
            //Adapted from
            //https://stackoverflow.com/questions/4354939/understanding-the-use-of-colormatrix-and-colormatrixcolorfilter-to-modify-a-draw
            val colorMatrix = it.matrices[outputIndex]
            if (radian == 0f) {
                colorMatrix.reset()
            } else {
                val array = colorMatrix.array
                val cosVal = cos(radian.toDouble()).toFloat()
                val sinVal = sin(radian.toDouble()).toFloat()
                array[0]=lumR + cosVal * (1 - lumR) + sinVal * -lumR
                array[1]=lumG + cosVal * -lumG + sinVal * -lumG
                array[2]=lumB + cosVal * -lumB + sinVal * (1 - lumB)
                //3=    0f,
                //4=    0f,
                array[5]=lumR + cosVal * -lumR + sinVal * 0.143f
                array[6]=lumG + cosVal * (1 - lumG) + sinVal * 0.140f
                array[7]=lumB + cosVal * -lumB + sinVal * -0.283f
                //8    0f,
                //9    0f,
                array[10]=lumR + cosVal * -lumR + sinVal * -(1 - lumR)
                array[11]=lumG + cosVal * -lumG + sinVal * lumG
                array[12]=lumB + cosVal * (1 - lumB) + sinVal * lumB
                //13    0f,
                //14    0f,
                //15    0f,
                //16    0f,
                //17    0f,
                //18    1f,
                //19    0f,
            }
            val colorFilterEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
            return applyEffect(it, colorFilterEffect, outputIndex)
        }
        throw RuntimeException("Not configured!")
    }

    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        params?.let {
            val blurRenderEffect = RenderEffect.createBlurEffect(radius, radius,
                Shader.TileMode.MIRROR)
            return applyEffect(it, blurRenderEffect, outputIndex)
        }
        throw RuntimeException("Not configured!")
    }

    override fun cleanup() {
        params?.let {
            it.imageReader.close()
            it.renderNode.discardDisplayList()
            it.hardwareRenderer.destroy()
            params = null
        }
    }

    inner class Params(val bitmap : Bitmap, numberOfOutputImages: Int) {
        @SuppressLint("WrongConstant")
        val imageReader = ImageReader.newInstance(bitmap.width, bitmap.height, PixelFormat.RGBA_8888, numberOfOutputImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderNode = RenderNode("RenderEffect")
        val hardwareBuffers = arrayOfNulls<HardwareBuffer>(numberOfOutputImages)
        val matrices = Array(numberOfOutputImages) {ColorMatrix()}
        val hardwareRenderer = HardwareRenderer()

        init {
            hardwareRenderer.setSurface(imageReader.surface)
            hardwareRenderer.setContentRoot(renderNode)
            renderNode.setPosition(0,0,imageReader.width, imageReader.height)
            for (matrix in matrices) {
                val array = matrix.array
                array[18] = 1f
            }
        }
    }

    companion object {
        const val lumR = 0.213f
        const val lumG = 0.715f
        const val lumB = 0.072f
    }
}