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

#ifndef RENDERSCRIPT_MIGRATION_SAMPLE_UTILS_H
#define RENDERSCRIPT_MIGRATION_SAMPLE_UTILS_H

#include <android/log.h>

// clang-format off
// vulkan_core.h must be included before vulkan_android.h
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_android.h>
// clang-format on

// Macros for logging
#define LOG_TAG "RENDERSCRIPT_MIGRATION_SAMPLE"
#define LOG(severity, ...) ((void)__android_log_print(ANDROID_LOG_##severity, LOG_TAG, __VA_ARGS__))
#define LOGE(...) LOG(ERROR, __VA_ARGS__)
#define LOGV(...) LOG(VERBOSE, __VA_ARGS__)

// Log an error and return false if condition fails
#define RET_CHECK(condition)                                                    \
    do {                                                                        \
        if (!(condition)) {                                                     \
            LOGE("Check failed at %s:%u - %s", __FILE__, __LINE__, #condition); \
            return false;                                                       \
        }                                                                       \
    } while (0)

// Invoke a Vulkan method, log an error and return false if the result is not VK_SUCCESS
#define CALL_VK(vkMethod, ...)                                                              \
    do {                                                                                    \
        auto _result = vkMethod(__VA_ARGS__);                                               \
        if (_result != VK_SUCCESS) {                                                        \
            LOGE("%s failed with %s at %s:%u", #vkMethod, vkResultToStr(_result), __FILE__, \
                 __LINE__);                                                                 \
            return false;                                                                   \
        }                                                                                   \
    } while (0)

namespace sample {

inline const char* vkResultToStr(VkResult result) {
    switch (result) {
        case VK_SUCCESS:
            return "VK_SUCCESS";
        case VK_NOT_READY:
            return "VK_NOT_READY";
        case VK_TIMEOUT:
            return "VK_TIMEOUT";
        case VK_EVENT_SET:
            return "VK_EVENT_SET";
        case VK_EVENT_RESET:
            return "VK_EVENT_RESET";
        case VK_INCOMPLETE:
            return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY:
            return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:
            return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED:
            return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST:
            return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED:
            return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT:
            return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT:
            return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT:
            return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER:
            return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_TOO_MANY_OBJECTS:
            return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_FORMAT_NOT_SUPPORTED:
            return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        case VK_ERROR_FRAGMENTED_POOL:
            return "VK_ERROR_FRAGMENTED_POOL";
        case VK_ERROR_OUT_OF_POOL_MEMORY:
            return "VK_ERROR_OUT_OF_POOL_MEMORY";
        case VK_ERROR_INVALID_EXTERNAL_HANDLE:
            return "VK_ERROR_INVALID_EXTERNAL_HANDLE";
        case VK_ERROR_SURFACE_LOST_KHR:
            return "VK_ERROR_SURFACE_LOST_KHR";
        case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
            return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
        case VK_SUBOPTIMAL_KHR:
            return "VK_SUBOPTIMAL_KHR";
        case VK_ERROR_OUT_OF_DATE_KHR:
            return "VK_ERROR_OUT_OF_DATE_KHR";
        case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR:
            return "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR";
        case VK_ERROR_VALIDATION_FAILED_EXT:
            return "VK_ERROR_VALIDATION_FAILED_EXT";
        case VK_ERROR_INVALID_SHADER_NV:
            return "VK_ERROR_INVALID_SHADER_NV";
        case VK_ERROR_FRAGMENTATION_EXT:
            return "VK_ERROR_FRAGMENTATION_EXT";
        case VK_ERROR_NOT_PERMITTED_EXT:
            return "VK_ERROR_NOT_PERMITTED_EXT";
        default:
            return "(Unknown VkResult)";
    }
}

// The following code defines RAII wrappers of Vulkan objects.
// The wrapper of Vk<Object> will be named as Vulkan<Object>, e.g. VkInstance -> VulkanInstance.

template <typename T_VkHandle>
class VulkanObjectBase {
   public:
    VulkanObjectBase() = default;

    // Disallow copy semantics to ensure the Vulkan object can only be freed once.
    VulkanObjectBase(const VulkanObjectBase&) = delete;
    VulkanObjectBase& operator=(const VulkanObjectBase&) = delete;

    // Move semantics to remove access to the Vulkan object from the wrapper object
    // that is being moved. This ensures the Vulkan object will be freed only once.
    VulkanObjectBase(VulkanObjectBase&& other) { *this = std::move(other); }
    VulkanObjectBase& operator=(VulkanObjectBase&& other) {
        mHandle = other.mHandle;
        other.mHandle = VK_NULL_HANDLE;
        return *this;
    }

    T_VkHandle handle() const { return mHandle; }
    T_VkHandle* pHandle() { return &mHandle; }

   protected:
    T_VkHandle mHandle = VK_NULL_HANDLE;
};

template <typename T_VkHandle, typename T_Destroyer>
class VulkanGlobalObject : public VulkanObjectBase<T_VkHandle> {
   public:
    ~VulkanGlobalObject() {
        if (this->mHandle != VK_NULL_HANDLE) {
            T_Destroyer::destroy(this->mHandle);
        }
    }
};

#define VULKAN_RAII_OBJECT(Type, destroyer)                                  \
    struct Vulkan##Type##Destroyer {                                         \
        static void destroy(Vk##Type handle) { destroyer(handle, nullptr); } \
    };                                                                       \
    using Vulkan##Type = VulkanGlobalObject<Vk##Type, Vulkan##Type##Destroyer>;

VULKAN_RAII_OBJECT(Instance, vkDestroyInstance);
VULKAN_RAII_OBJECT(Device, vkDestroyDevice);

#undef VULKAN_RAII_OBJECT

// Vulkan objects that is created/allocated with an instance
template <typename T_VkHandle, typename T_Destroyer>
class VulkanObjectFromInstance : public VulkanObjectBase<T_VkHandle> {
   public:
    explicit VulkanObjectFromInstance(VkInstance instance) : mInstance(instance) {}
    VulkanObjectFromInstance(VulkanObjectFromInstance&& other) { *this = std::move(other); }
    VulkanObjectFromInstance& operator=(VulkanObjectFromInstance&& other) {
        VulkanObjectBase<T_VkHandle>::operator=(std::move(other));
        mInstance = other.mInstance;
        return *this;
    }
    ~VulkanObjectFromInstance() {
        if (this->mHandle != VK_NULL_HANDLE) {
            T_Destroyer::destroy(mInstance, this->mHandle);
        }
    }

   protected:
    VkInstance mInstance = VK_NULL_HANDLE;
};

// Vulkan objects that is created/allocated with a device
template <typename T_VkHandle, typename T_Destroyer>
class VulkanObjectFromDevice : public VulkanObjectBase<T_VkHandle> {
   public:
    explicit VulkanObjectFromDevice(VkDevice device) : mDevice(device) {}
    VulkanObjectFromDevice(VulkanObjectFromDevice&& other) { *this = std::move(other); }
    VulkanObjectFromDevice& operator=(VulkanObjectFromDevice&& other) {
        VulkanObjectBase<T_VkHandle>::operator=(std::move(other));
        mDevice = other.mDevice;
        return *this;
    }
    ~VulkanObjectFromDevice() {
        if (this->mHandle != VK_NULL_HANDLE) {
            T_Destroyer::destroy(mDevice, this->mHandle);
        }
    }

   protected:
    VkDevice mDevice = VK_NULL_HANDLE;
};

#define VULKAN_RAII_OBJECT_FROM_DEVICE(Type, destroyer)         \
    struct Vulkan##Type##Destroyer {                            \
        static void destroy(VkDevice device, Vk##Type handle) { \
            destroyer(device, handle, nullptr);                 \
        }                                                       \
    };                                                          \
    using Vulkan##Type = VulkanObjectFromDevice<Vk##Type, Vulkan##Type##Destroyer>;

VULKAN_RAII_OBJECT_FROM_DEVICE(CommandPool, vkDestroyCommandPool);
VULKAN_RAII_OBJECT_FROM_DEVICE(DescriptorPool, vkDestroyDescriptorPool);
VULKAN_RAII_OBJECT_FROM_DEVICE(Buffer, vkDestroyBuffer);
VULKAN_RAII_OBJECT_FROM_DEVICE(DeviceMemory, vkFreeMemory);
VULKAN_RAII_OBJECT_FROM_DEVICE(DescriptorSetLayout, vkDestroyDescriptorSetLayout);
VULKAN_RAII_OBJECT_FROM_DEVICE(PipelineLayout, vkDestroyPipelineLayout);
VULKAN_RAII_OBJECT_FROM_DEVICE(ShaderModule, vkDestroyShaderModule);
VULKAN_RAII_OBJECT_FROM_DEVICE(Pipeline, vkDestroyPipeline);
VULKAN_RAII_OBJECT_FROM_DEVICE(Image, vkDestroyImage);
VULKAN_RAII_OBJECT_FROM_DEVICE(Sampler, vkDestroySampler);
VULKAN_RAII_OBJECT_FROM_DEVICE(ImageView, vkDestroyImageView);
VULKAN_RAII_OBJECT_FROM_DEVICE(Semaphore, vkDestroySemaphore);

#undef VULKAN_RAII_OBJECT_FROM_DEVICE

// Vulkan objects that is allocated from a pool
template <typename T_VkHandle, typename T_VkPoolHandle, typename T_Destroyer>
class VulkanObjectFromPool : public VulkanObjectBase<T_VkHandle> {
   public:
    VulkanObjectFromPool(VkDevice device, T_VkPoolHandle pool) : mDevice(device), mPool(pool) {}
    VulkanObjectFromPool(VulkanObjectFromPool&& other) { *this = std::move(other); }
    VulkanObjectFromPool& operator=(VulkanObjectFromPool&& other) {
        VulkanObjectBase<T_VkHandle>::operator=(std::move(other));
        mDevice = other.mDevice;
        mPool = other.mPool;
        return *this;
    }
    ~VulkanObjectFromPool() {
        if (this->mHandle != VK_NULL_HANDLE) {
            T_Destroyer::destroy(mDevice, mPool, this->mHandle);
        }
    }

   protected:
    VkDevice mDevice = VK_NULL_HANDLE;
    T_VkPoolHandle mPool = VK_NULL_HANDLE;
};

#define VULKAN_RAII_OBJECT_FROM_POOL(Type, VkPoolType, destroyer)                \
    struct Vulkan##Type##Destroyer {                                             \
        static void destroy(VkDevice device, VkPoolType pool, Vk##Type handle) { \
            destroyer(device, pool, 1, &handle);                                 \
        }                                                                        \
    };                                                                           \
    using Vulkan##Type = VulkanObjectFromPool<Vk##Type, VkPoolType, Vulkan##Type##Destroyer>;

VULKAN_RAII_OBJECT_FROM_POOL(CommandBuffer, VkCommandPool, vkFreeCommandBuffers);
VULKAN_RAII_OBJECT_FROM_POOL(DescriptorSet, VkDescriptorPool, vkFreeDescriptorSets);

#undef VULKAN_RAII_OBJECT_FROM_POOL

}  // namespace sample

#endif  // RENDERSCRIPT_MIGRATION_SAMPLE_UTILS_H
