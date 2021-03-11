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
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.hardware.HardwareBuffer


class VulkanImageProcessor(context: Context) : ImageProcessor {
    override val name = "Vulkan"

    private var mVulkanProcessor = initVulkanProcessor(context.assets)

    init {
        if (mVulkanProcessor == 0L) {
            throw RuntimeException("Failed to initialize Vulkan processor")
        }
    }

    private lateinit var mOutputImages: Array<Bitmap>

    // Native methods

    // Initialize the image processor backed by Vulkan.
    // Return a non-zero handle on success, and 0L if failed.
    private external fun initVulkanProcessor(assetManager: AssetManager): Long

    // Set the input image from bitmap and allocate output images backed by AHardwareBuffers.
    // Return true on success, and false if failed.
    private external fun configureInputAndOutput(
        processor: Long,
        inputBitmap: Bitmap,
        numberOfOutputImages: Int
    ): Boolean

    // Get the HardwareBuffer of the target output. This method must be invoked after
    // configureInputAndOutput, and index must be within [0, numberOfOutputImages).
    // Return null if failed.
    private external fun getOutputHardwareBuffer(processor: Long, index: Int): HardwareBuffer?

    // Apply the hue rotation filter in Vulkan and write the results to the indexed output image.
    private external fun rotateHue(processor: Long, radian: Float, outputIndex: Int): Boolean

    // Apply the blur filter in Vulkan and write the results to the indexed output image.
    private external fun blur(processor: Long, radius: Float, outputIndex: Int): Boolean

    // Frees up any underlying native resources. After calling this method, the Vulkan processor
    // must not be used in any way.
    private external fun destroyVulkanProcessor(processor: Long)

    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        val success = configureInputAndOutput(mVulkanProcessor, inputImage, numberOfOutputImages)
        if (!success) throw RuntimeException("Failed to configureInputAndOutput")
        mOutputImages = Array(numberOfOutputImages) { i ->
            val buffer = getOutputHardwareBuffer(mVulkanProcessor, i)
                ?: throw RuntimeException("Failed to getOutputHardwareBuffer at index $i")
            val outputImage = Bitmap.wrapHardwareBuffer(buffer, null)
                ?: throw RuntimeException("Failed to wrapHardwareBuffer at index $i")
            buffer.close()
            outputImage
        }
    }

    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        val success = rotateHue(mVulkanProcessor, radian, outputIndex)
        if (!success) throw RuntimeException("Failed to rotateHue")
        return mOutputImages[outputIndex]
    }

    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        val success = blur(mVulkanProcessor, radius, outputIndex)
        if (!success) throw RuntimeException("Failed to blur")
        return mOutputImages[outputIndex]
    }

    override fun cleanup() {
        if (mVulkanProcessor != 0L) {
            destroyVulkanProcessor(mVulkanProcessor)
            mVulkanProcessor = 0L
        }
    }
}
