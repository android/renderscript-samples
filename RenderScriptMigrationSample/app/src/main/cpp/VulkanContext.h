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

#ifndef RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_CONTEXT_H
#define RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_CONTEXT_H

#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>

#include <memory>
#include <optional>
#include <vector>

#include "Utils.h"

namespace sample {

// VulkanContext manages the Vulkan environment and resource objects that are shared by multiple
// compute pipelines.
class VulkanContext {
   public:
    // Create the managed Vulkan objects. If enableDebug is true, the Vulkan instance will be
    // created with the validation layer "VK_LAYER_KHRONOS_validation".
    static std::unique_ptr<VulkanContext> create(bool enableDebug);

    // Prefer VulkanContext::create
    VulkanContext() : mDescriptorPool(VK_NULL_HANDLE), mCommandPool(VK_NULL_HANDLE) {}

    // Getters of the managed Vulkan objects
    VkDevice device() const { return mDevice.handle(); }
    VkQueue queue() const { return mQueue; }
    VkCommandPool commandPool() const { return mCommandPool.handle(); }
    VkDescriptorPool descriptorPool() const { return mDescriptorPool.handle(); }

    uint32_t getWorkGroupSize() const { return mWorkGroupSize; }

    // Find a suitable memory type that matches the memoryTypeBits and the required properties.
    std::optional<uint32_t> findMemoryType(uint32_t memoryTypeBits, VkFlags properties) const;

    // Create a semaphore with the managed device.
    bool createSemaphore(VkSemaphore* semaphore) const;

    // Create a buffer and its memory.
    bool createBuffer(size_t size, VkFlags bufferUsage, VkFlags memoryProperties, VkBuffer* buffer,
                      VkDeviceMemory* memory) const;

    // Create a command buffer with VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, and begin command
    // buffer recording.
    bool beginSingleTimeCommand(VkCommandBuffer* commandBuffer) const;

    // End the command buffer recording, submit it to the queue, and wait until it is finished.
    bool endAndSubmitSingleTimeCommand(VkCommandBuffer commandBuffer) const;

   private:
    // Initialization
    bool checkInstanceVersion();
    bool createInstance(bool enableDebug);
    bool pickPhysicalDeviceAndQueueFamily();
    bool createDevice();
    bool createPools();

    // Instance
    uint32_t mInstanceVersion = 0;
    VulkanInstance mInstance;

    // Physical device and queue family
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties mPhysicalDeviceProperties;
    VkPhysicalDeviceMemoryProperties mPhysicalDeviceMemoryProperties;
    uint32_t mQueueFamilyIndex = 0;
    uint32_t mWorkGroupSize = 0;

    // Logical device and queue
    VulkanDevice mDevice;
    VkQueue mQueue = VK_NULL_HANDLE;

    // Pools
    VulkanDescriptorPool mDescriptorPool;
    VulkanCommandPool mCommandPool;
};

}  // namespace sample

#endif  // RENDERSCRIPT_MIGRATION_SAMPLE_VULKAN_CONTEXT_H
