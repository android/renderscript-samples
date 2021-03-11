/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.rsmigration

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.*
import kotlin.math.*


class RenderScriptImageProcessor(context: Context, useIntrinsic: Boolean) : ImageProcessor {
    override val name = "RenderScript " + if (useIntrinsic) "Intrinsics" else "Scripts"

    // Renderscript scripts
    private val mRS: RenderScript = RenderScript.create(context)
    private val mIntrinsicColorMatrix = ScriptIntrinsicColorMatrix.create(mRS)
    private val mIntrinsicBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS))
    private val mScriptColorMatrix = ScriptC_colormatrix(mRS)
    private val mScriptBlur = ScriptC_blur(mRS)
    private val mUseIntrinsic = useIntrinsic

    // Input image
    private lateinit var mInAllocation: Allocation

    // Intermediate buffers for the two-pass gaussian blur script
    private lateinit var mTempAllocations: Array<Allocation>

    // Output images
    private lateinit var mOutputImages: Array<Bitmap>
    private lateinit var mOutAllocations: Array<Allocation>


    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        if (numberOfOutputImages <= 0) {
            throw RuntimeException("Invalid number of output images: $numberOfOutputImages")
        }

        // Input allocation
        mInAllocation = Allocation.createFromBitmap(mRS, inputImage)

        // This buffer is only used as the intermediate result in script blur,
        // so only USAGE_SCRIPT is needed.
        mTempAllocations = Allocation.createAllocations(
            mRS,
            Type.createXY(mRS, Element.F32_4(mRS), inputImage.width, inputImage.height),
            Allocation.USAGE_SCRIPT,
            /*numAlloc=*/2
        )

        // Output images and allocations
        mOutputImages = Array(numberOfOutputImages) {
            Bitmap.createBitmap(inputImage.width, inputImage.height, inputImage.config)
        }
        mOutAllocations =
            Array(numberOfOutputImages) { i -> Allocation.createFromBitmap(mRS, mOutputImages[i]) }

        // Update dimensional variables in blur kernel
        mScriptBlur._gWidth = inputImage.width
        mScriptBlur._gHeight = inputImage.height
    }

    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        // Set HUE rotation matrix
        // The matrix below performs a combined operation of,
        // RGB->HSV transform * HUE rotation * HSV->RGB transform
        val cos = cos(radian.toDouble())
        val sin = sin(radian.toDouble())
        val mat = Matrix3f()
        mat[0, 0] = (.299 + .701 * cos + .168 * sin).toFloat()
        mat[1, 0] = (.587 - .587 * cos + .330 * sin).toFloat()
        mat[2, 0] = (.114 - .114 * cos - .497 * sin).toFloat()
        mat[0, 1] = (.299 - .299 * cos - .328 * sin).toFloat()
        mat[1, 1] = (.587 + .413 * cos + .035 * sin).toFloat()
        mat[2, 1] = (.114 - .114 * cos + .292 * sin).toFloat()
        mat[0, 2] = (.299 - .300 * cos + 1.25 * sin).toFloat()
        mat[1, 2] = (.587 - .588 * cos - 1.05 * sin).toFloat()
        mat[2, 2] = (.114 + .886 * cos - .203 * sin).toFloat()

        // Invoke filter kernel
        if (mUseIntrinsic) {
            mIntrinsicColorMatrix.setColorMatrix(mat)
            mIntrinsicColorMatrix.forEach(mInAllocation, mOutAllocations[outputIndex])
        } else {
            mScriptColorMatrix.invoke_setMatrix(mat)
            mScriptColorMatrix.forEach_root(mInAllocation, mOutAllocations[outputIndex])
        }

        // Copy to bitmap, this should cause a synchronization rather than a full copy.
        mOutAllocations[outputIndex].copyTo(mOutputImages[outputIndex])
        return mOutputImages[outputIndex]
    }

    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        if (radius < 1.0f || radius > 25.0f) {
            throw RuntimeException("Invalid radius ${radius}, must be within [1.0, 25.0]")
        }
        if (mUseIntrinsic) {
            // Set blur kernel size
            mIntrinsicBlur.setRadius(radius)

            // Invoke filter kernel
            mIntrinsicBlur.setInput(mInAllocation)
            mIntrinsicBlur.forEach(mOutAllocations[outputIndex])
        } else {
            // Calculate gaussian kernel, this is equivalent to ComputeGaussianWeights at
            // https://cs.android.com/android/platform/superproject/+/master:frameworks/rs/cpu_ref/rsCpuIntrinsicBlur.cpp;l=57
            val sigma = 0.4f * radius + 0.6f
            val coeff1 = 1.0f / (sqrt(2 * Math.PI) * sigma).toFloat()
            val coeff2 = -1.0f / (2 * sigma * sigma)
            val iRadius = ceil(radius).toInt()
            val kernel = FloatArray(51) { i ->
                if (i > (iRadius * 2 + 1)) {
                    0.0f
                } else {
                    val r = (i - iRadius).toFloat()
                    coeff1 * (Math.E.toFloat().pow(coeff2 * r * r))
                }
            }
            val normalizeFactor = 1.0f / kernel.sum()
            kernel.forEachIndexed { i, v -> kernel[i] = v * normalizeFactor }

            // Apply a two-pass blur algorithm: a horizontal blur kernel followed by a vertical
            // blur kernel. This is equivalent to, but more efficient than applying a 2D blur
            // filter in a single pass. The two-pass blur algorithm has two kernels, each of
            // time complexity O(iRadius), while the single-pass algorithm has only one kernel,
            // but the time complexity is O(iRadius^2).
            mScriptBlur._gRadius = iRadius
            mScriptBlur._gKernel = kernel
            mScriptBlur._gScratch1 = mTempAllocations[0]
            mScriptBlur._gScratch2 = mTempAllocations[1]
            mScriptBlur.forEach_copyIn(mInAllocation, mTempAllocations[0])
            mScriptBlur.forEach_horizontal(mTempAllocations[1])
            mScriptBlur.forEach_vertical(mOutAllocations[outputIndex])
        }

        // Copy to bitmap, this should cause a synchronization rather than a full copy.
        mOutAllocations[outputIndex].copyTo(mOutputImages[outputIndex])
        return mOutputImages[outputIndex]
    }

    override fun cleanup() {}
}
