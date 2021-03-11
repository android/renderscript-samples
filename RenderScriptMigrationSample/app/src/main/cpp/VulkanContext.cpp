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

#include "VulkanContext.h"

#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan_core.h>

#include <array>
#include <cmath>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "Utils.h"

namespace sample {
namespace {

// Choose the work group size of the compute shader.
// In this sample app, we are using a square execution dimension.
uint32_t chooseWorkGroupSize(const VkPhysicalDeviceLimits& limits) {
    // Use 64 as the baseline.
    uint32_t size = 64;

    // Make sure the size does not exceed the limit along the X and Y axis.
    size = std::min<uint32_t>(size, limits.maxComputeWorkGroupSize[0]);
    size = std::min<uint32_t>(size, limits.maxComputeWorkGroupSize[1]);

    // Make sure the total number of invocations does not exceed the limit.
    size = std::min<uint32_t>(
            size, static_cast<uint32_t>(std::sqrt(limits.maxComputeWorkGroupInvocations)));

    // We prefer the workgroup size to be a multiple of 4.
    size &= ~(3u);

    LOGV("maxComputeWorkGroupInvocations: %d, maxComputeWorkGroupSize: (%d, %d)",
         limits.maxComputeWorkGroupInvocations, limits.maxComputeWorkGroupSize[0],
         limits.maxComputeWorkGroupSize[1]);
    LOGV("Choose workgroup size: (%d, %d)", size, size);
    return size;
}

}  // namespace

std::unique_ptr<VulkanContext> VulkanContext::create(bool enableDebug) {
    auto vk = std::make_unique<VulkanContext>();
    const bool success = vk->checkInstanceVersion() && vk->createInstance(enableDebug) &&
                         vk->pickPhysicalDeviceAndQueueFamily() && vk->createDevice() &&
                         vk->createPools();
    return success ? std::move(vk) : nullptr;
}

bool VulkanContext::checkInstanceVersion() {
    CALL_VK(vkEnumerateInstanceVersion, &mInstanceVersion);
    if (VK_VERSION_MAJOR(mInstanceVersion) != 1) {
        LOGE("incompatible Vulkan version");
        return false;
    }
    LOGV("Vulkan instance version: %d.%d", VK_VERSION_MAJOR(mInstanceVersion),
         VK_VERSION_MINOR(mInstanceVersion));
    return true;
}

bool VulkanContext::createInstance(bool enableDebug) {
    // Required instance layers
    std::vector<const char*> instanceLayers;
    if (enableDebug) {
        instanceLayers.push_back("VK_LAYER_KHRONOS_validation");
    }

    // Required instance extensions
    std::vector<const char*> instanceExtensions = {
            VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
            VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
    };
    if (enableDebug) {
        instanceExtensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }

    // Create instance
    const VkApplicationInfo applicationDesc = {
            .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
            .pApplicationName = "renderscript_migration_sample",
            .applicationVersion = VK_MAKE_VERSION(0, 0, 1),
            .apiVersion = static_cast<uint32_t>((VK_VERSION_MINOR(mInstanceVersion) >= 1)
                                                        ? VK_API_VERSION_1_1
                                                        : VK_API_VERSION_1_0),
    };
    const VkInstanceCreateInfo instanceDesc = {
            .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            .pApplicationInfo = &applicationDesc,
            .enabledLayerCount = static_cast<uint32_t>(instanceLayers.size()),
            .ppEnabledLayerNames = instanceLayers.data(),
            .enabledExtensionCount = static_cast<uint32_t>(instanceExtensions.size()),
            .ppEnabledExtensionNames = instanceExtensions.data(),
    };
    CALL_VK(vkCreateInstance, &instanceDesc, nullptr, mInstance.pHandle());
    return true;
}

bool VulkanContext::pickPhysicalDeviceAndQueueFamily() {
    uint32_t numDevices = 0;
    CALL_VK(vkEnumeratePhysicalDevices, mInstance.handle(), &numDevices, nullptr);
    std::vector<VkPhysicalDevice> devices(numDevices);
    CALL_VK(vkEnumeratePhysicalDevices, mInstance.handle(), &numDevices, devices.data());

    // Pick the first device with a compute queue
    for (auto device : devices) {
        uint32_t numQueueFamilies = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &numQueueFamilies, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(numQueueFamilies);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &numQueueFamilies, queueFamilies.data());

        uint32_t qf = 0;
        bool haveQf = false;
        for (uint32_t i = 0; i < queueFamilies.size(); i++) {
            if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                qf = i;
                haveQf = true;
                break;
            }
        }
        if (!haveQf) continue;
        mPhysicalDevice = device;
        mQueueFamilyIndex = qf;
        break;
    }
    RET_CHECK(mPhysicalDevice != VK_NULL_HANDLE);
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &mPhysicalDeviceProperties);
    mWorkGroupSize = chooseWorkGroupSize(mPhysicalDeviceProperties.limits);
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &mPhysicalDeviceMemoryProperties);
    LOGV("Using physical device '%s'", mPhysicalDeviceProperties.deviceName);
    return true;
}

bool VulkanContext::createDevice() {
    // Required device extensions
    // These extensions are required to import an AHardwareBuffer to Vulkan.
    std::vector<const char*> deviceExtensions = {
            VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
            VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    };

    // Create logical device
    const float queuePriority = 1.0f;
    const VkDeviceQueueCreateInfo queueDesc = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .queueFamilyIndex = mQueueFamilyIndex,
            .queueCount = 1,
            .pQueuePriorities = &queuePriority,
    };
    const VkDeviceCreateInfo deviceDesc = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queueDesc,
            .enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size()),
            .ppEnabledExtensionNames = deviceExtensions.data(),
            .pEnabledFeatures = nullptr,
    };
    CALL_VK(vkCreateDevice, mPhysicalDevice, &deviceDesc, nullptr, mDevice.pHandle());
    vkGetDeviceQueue(mDevice.handle(), mQueueFamilyIndex, 0, &mQueue);
    return true;
}

bool VulkanContext::createPools() {
    // Create descriptor pool
    mDescriptorPool = VulkanDescriptorPool(mDevice.handle());
    const std::vector<VkDescriptorPoolSize> descriptorPoolSizes = {
            {
                    .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    // We have three pipelines, each need 1 combined image sampler descriptor.
                    .descriptorCount = 3,
            },
            {
                    .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    // We have three pipelines, each need 1 storage image descriptor.
                    .descriptorCount = 3,
            },
            {
                    .type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    // We have three pipelines, each need 1 uniform buffer.
                    .descriptorCount = 3,
            },
    };
    const VkDescriptorPoolCreateInfo descriptorPoolDesc = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .maxSets = 3,
            .poolSizeCount = static_cast<uint32_t>(descriptorPoolSizes.size()),
            .pPoolSizes = descriptorPoolSizes.data(),
    };
    CALL_VK(vkCreateDescriptorPool, mDevice.handle(), &descriptorPoolDesc, nullptr,
            mDescriptorPool.pHandle());

    // Create command pool
    mCommandPool = VulkanCommandPool(mDevice.handle());
    const VkCommandPoolCreateInfo cmdpoolDesc = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = mQueueFamilyIndex,
    };
    CALL_VK(vkCreateCommandPool, mDevice.handle(), &cmdpoolDesc, nullptr, mCommandPool.pHandle());
    return true;
}

std::optional<uint32_t> VulkanContext::findMemoryType(uint32_t memoryTypeBits,
                                                      VkFlags properties) const {
    for (uint32_t i = 0; i < mPhysicalDeviceMemoryProperties.memoryTypeCount; i++) {
        if (memoryTypeBits & 1u) {
            if ((mPhysicalDeviceMemoryProperties.memoryTypes[i].propertyFlags & properties) ==
                properties) {
                return i;
            }
        }
        memoryTypeBits >>= 1u;
    }
    return std::nullopt;
}

bool VulkanContext::createSemaphore(VkSemaphore* semaphore) const {
    if (semaphore == nullptr) return false;
    const VkSemaphoreCreateInfo semaphoreCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
    };
    CALL_VK(vkCreateSemaphore, mDevice.handle(), &semaphoreCreateInfo, nullptr, semaphore);
    return true;
}

bool VulkanContext::createBuffer(size_t size, VkFlags bufferUsage, VkFlags memoryProperties,
                                 VkBuffer* buffer, VkDeviceMemory* memory) const {
    if (buffer == nullptr || memory == nullptr) return false;

    // Create VkBuffer
    const VkBufferCreateInfo bufferCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = size,
            .usage = bufferUsage,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    CALL_VK(vkCreateBuffer, mDevice.handle(), &bufferCreateInfo, nullptr, buffer);

    // Allocate memory for the buffer
    VkMemoryRequirements memoryRequirements;
    vkGetBufferMemoryRequirements(mDevice.handle(), *buffer, &memoryRequirements);
    const auto memoryTypeIndex =
            findMemoryType(memoryRequirements.memoryTypeBits, memoryProperties);
    RET_CHECK(memoryTypeIndex.has_value());
    const VkMemoryAllocateInfo allocateInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .pNext = nullptr,
            .allocationSize = memoryRequirements.size,
            .memoryTypeIndex = memoryTypeIndex.value(),
    };
    CALL_VK(vkAllocateMemory, mDevice.handle(), &allocateInfo, nullptr, memory);

    vkBindBufferMemory(mDevice.handle(), *buffer, *memory, 0);
    return true;
}

bool VulkanContext::beginSingleTimeCommand(VkCommandBuffer* commandBuffer) const {
    if (commandBuffer == nullptr) return false;

    // Allocate a command buffer
    const VkCommandBufferAllocateInfo commandBufferAllocateInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .pNext = nullptr,
            .commandPool = mCommandPool.handle(),
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = 1,
    };
    CALL_VK(vkAllocateCommandBuffers, mDevice.handle(), &commandBufferAllocateInfo, commandBuffer);

    // Begin command buffer recording
    const VkCommandBufferBeginInfo beginInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
            .pInheritanceInfo = nullptr,
    };
    CALL_VK(vkBeginCommandBuffer, *commandBuffer, &beginInfo);
    return true;
}

bool VulkanContext::endAndSubmitSingleTimeCommand(VkCommandBuffer commandBuffer) const {
    // End command buffer recording
    CALL_VK(vkEndCommandBuffer, commandBuffer);

    // Submit command buffer to the compute queue
    const VkSubmitInfo submitInfo = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = 0,
            .pWaitSemaphores = nullptr,
            .pWaitDstStageMask = nullptr,
            .commandBufferCount = 1,
            .pCommandBuffers = &commandBuffer,
            .signalSemaphoreCount = 0,
            .pSignalSemaphores = nullptr,
    };
    CALL_VK(vkQueueSubmit, mQueue, 1, &submitInfo, VK_NULL_HANDLE);

    // Wait for the command to finish
    CALL_VK(vkQueueWaitIdle, mQueue);
    return true;
}

}  // namespace sample
