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

#ifndef RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_RESOURCES_H
#define RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_RESOURCES_H

#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <jni.h>

#include <memory>
#include <optional>
#include <vector>

#include "Utils.h"
#include "VulkanContext.h"

namespace sample {

class Buffer {
   public:
    // Create a buffer and allocate the memory.
    static std::unique_ptr<Buffer> create(const VulkanContext* context, uint32_t size,
                                          VkBufferUsageFlags usage,
                                          VkMemoryPropertyFlags properties);

    // Prefer Buffer::create
    Buffer(const VulkanContext* context, uint32_t size)
        : mContext(context), mSize(size), mBuffer(context->device()), mMemory(context->device()) {}

    // Set the buffer content from the data. The buffer must be created with host-visible and
    // host-coherent properties.
    bool copyFrom(const void* data);

    VkBuffer getBufferHandle() const { return mBuffer.handle(); }
    VkDescriptorBufferInfo getDescriptor() const { return {mBuffer.handle(), 0, mSize}; }

   private:
    bool initialize(VkBufferUsageFlags usage, VkMemoryPropertyFlags properties);

    const VulkanContext* mContext;
    uint32_t mSize;

    // Managed handles
    VulkanBuffer mBuffer;
    VulkanDeviceMemory mMemory;
};

class Image {
   public:
    // Create a image backed by device local memory. The layout is VK_IMAGE_LAYOUT_UNDEFINED
    // after the creation.
    static std::unique_ptr<Image> createDeviceLocal(const VulkanContext* context, uint32_t width,
                                                    uint32_t height, VkImageUsageFlags usage);

    // Create a image backed by device local memory, and initialize the memory from a bitmap image.
    // The image is created with usage VK_IMAGE_USAGE_TRANSFER_DST_BIT and
    // VK_IMAGE_USAGE_SAMPLED_BIT as an input of compute shader. The layout is set to
    // VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL after the creation.
    static std::unique_ptr<Image> createFromBitmap(const VulkanContext* context, JNIEnv* env,
                                                   jobject bitmap);

    // Create a image backed by the given AHardwareBuffer. The image will keep a reference to the
    // AHardwareBuffer so that callers can safely close buffer.
    // The image is created with usage VK_IMAGE_USAGE_TRANSFER_DST_BIT.
    // The layout is set to VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL after the creation.
    static std::unique_ptr<Image> createFromAHardwareBuffer(const VulkanContext* context,
                                                            AHardwareBuffer* buffer);

    // Prefer static factory methods
    Image(const VulkanContext* context) : Image(context, 0u, 0u) {}
    Image(const VulkanContext* context, uint32_t width, uint32_t height)
        : mContext(context),
          mWidth(width),
          mHeight(height),
          mImage(context->device()),
          mMemory(context->device()),
          mSampler(context->device()),
          mImageView(context->device()) {}

    ~Image() {
        if (mBuffer != nullptr) {
            AHardwareBuffer_release(mBuffer);
        }
    }

    uint32_t width() const { return mWidth; }
    uint32_t height() const { return mHeight; }
    VkImage getImageHandle() const { return mImage.handle(); }
    AHardwareBuffer* getAHardwareBuffer() { return mBuffer; }
    VkDescriptorImageInfo getDescriptor() const {
        return {mSampler.handle(), mImageView.handle(), mLayout};
    }

    // Record a layout transition image barrier to the command buffer.
    // If preserveData is false, the image content may not be preserved during the layout
    // transformation by treating the original layout as VK_IMAGE_LAYOUT_UNDEFINED.
    void recordLayoutTransitionBarrier(VkCommandBuffer cmd, VkImageLayout newLayout,
                                       bool preserveData = true);

   private:
    // Initialization
    bool createDeviceLocalImage(VkImageUsageFlags usage);
    bool createImageFromAHardwareBuffer(AHardwareBuffer* buffer);
    bool createSampler();
    bool createImageView();

    // Copy the bitmap pixels to the image device memory. The image must be created with
    // VK_IMAGE_USAGE_TRANSFER_DST_BIT.
    bool setContentFromBitmap(JNIEnv* env, jobject bitmap);

    // Change the image layout from mLayout to newLayout.
    bool transitionLayout(VkImageLayout newLayout);

    // Context
    const VulkanContext* mContext;

    uint32_t mWidth;
    uint32_t mHeight;

    // The managed AHardwareBuffer handle. Only valid if the image is created from
    // Image::createFromAHardwareBuffer.
    AHardwareBuffer* mBuffer = nullptr;

    // Managed handles
    VulkanImage mImage;
    VulkanDeviceMemory mMemory;
    VulkanSampler mSampler;
    VulkanImageView mImageView;
    VkImageLayout mLayout = VK_IMAGE_LAYOUT_UNDEFINED;
};

}  // namespace sample

#endif  // RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_RESOURCES_H
