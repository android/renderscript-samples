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

#include "ComputePipeline.h"

#include <android/asset_manager_jni.h>

#include <vector>

#include "Utils.h"
#include "VulkanContext.h"
#include "VulkanResources.h"

namespace sample {
namespace {

uint32_t ceilOfDiv(uint32_t lhs, uint32_t rhs) {
    return (lhs + rhs - 1) / rhs;
}

bool createShaderModuleFromAsset(VkDevice device, const char* shaderFilePath,
                                 AAssetManager* assetManager, VkShaderModule* shaderModule) {
    // Read shader file from asset.
    AAsset* shaderFile = AAssetManager_open(assetManager, shaderFilePath, AASSET_MODE_BUFFER);
    RET_CHECK(shaderFile != nullptr);
    const size_t shaderSize = AAsset_getLength(shaderFile);
    std::vector<char> shader(shaderSize);
    int status = AAsset_read(shaderFile, shader.data(), shaderSize);
    AAsset_close(shaderFile);
    RET_CHECK(status >= 0);

    // Create shader module.
    const VkShaderModuleCreateInfo shaderDesc = {
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .flags = 0,
            .codeSize = shaderSize,
            .pCode = reinterpret_cast<const uint32_t*>(shader.data()),
    };
    CALL_VK(vkCreateShaderModule, device, &shaderDesc, nullptr, shaderModule);
    return true;
}

}  // namespace

std::unique_ptr<ComputePipeline> ComputePipeline::create(const VulkanContext* context,
                                                         const char* shader,
                                                         AAssetManager* assetManager,
                                                         uint32_t pushConstantSize,
                                                         bool useUniformBuffer) {
    auto pipeline = std::make_unique<ComputePipeline>(context, pushConstantSize);
    const bool success = pipeline->createDescriptorSet(useUniformBuffer) &&
                         pipeline->createComputePipeline(shader, assetManager);
    return success ? std::move(pipeline) : nullptr;
}

bool ComputePipeline::createDescriptorSet(bool useUniformBuffer) {
    // Create descriptor set layout
    std::vector<VkDescriptorSetLayoutBinding> descriptorsetLayoutBinding = {
            {
                    .binding = 0,  // input image
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .descriptorCount = 1,
                    .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            },
            {
                    .binding = 1,  // output image
                    .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    .descriptorCount = 1,
                    .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            },

    };
    if (useUniformBuffer) {
        descriptorsetLayoutBinding.push_back({
                .binding = 2,  // parameters
                .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        });
    }
    const VkDescriptorSetLayoutCreateInfo descriptorsetLayoutDesc = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = static_cast<uint32_t>(descriptorsetLayoutBinding.size()),
            .pBindings = descriptorsetLayoutBinding.data(),
    };
    CALL_VK(vkCreateDescriptorSetLayout, mContext->device(), &descriptorsetLayoutDesc, nullptr,
            mDescriptorSetLayout.pHandle());

    // Allocate descriptor set
    const VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = mContext->descriptorPool(),
            .descriptorSetCount = 1,
            .pSetLayouts = mDescriptorSetLayout.pHandle(),
    };
    CALL_VK(vkAllocateDescriptorSets, mContext->device(), &descriptorSetAllocateInfo,
            &mDescriptorSet);
    return true;
}

bool ComputePipeline::updateDescriptorSets(const Image& inputImage, const Image& outputImage,
                                           const Buffer* uniformBuffer) {
    const auto inputImageInfo = inputImage.getDescriptor();
    const auto outputImageInfo = outputImage.getDescriptor();
    std::vector<VkWriteDescriptorSet> writeDescriptorSet = {
            {
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .dstSet = mDescriptorSet,
                    .dstBinding = 0,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .pImageInfo = &inputImageInfo,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            },
            {
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .dstSet = mDescriptorSet,
                    .dstBinding = 1,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    .pImageInfo = &outputImageInfo,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            },
    };
    VkDescriptorBufferInfo uboInfo{};
    if (uniformBuffer != nullptr) {
        uboInfo = uniformBuffer->getDescriptor();
        writeDescriptorSet.push_back({
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .dstSet = mDescriptorSet,
                .dstBinding = 2,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &uboInfo,
                .pTexelBufferView = nullptr,
        });
    }
    vkUpdateDescriptorSets(mContext->device(), static_cast<uint32_t>(writeDescriptorSet.size()),
                           writeDescriptorSet.data(), 0, nullptr);
    return true;
}

bool ComputePipeline::createComputePipeline(const char* shader, AAssetManager* assetManager) {
    // Create shader module
    VulkanShaderModule shaderModule(mContext->device());
    RET_CHECK(createShaderModuleFromAsset(mContext->device(), shader, assetManager,
                                          shaderModule.pHandle()));

    // Create pipeline layout
    const bool hasPushConstant = mPushConstantSize > 0;
    const VkPushConstantRange pushConstantRange = {
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .offset = 0,
            .size = mPushConstantSize,
    };
    const VkPipelineLayoutCreateInfo layoutDesc = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 1,
            .pSetLayouts = mDescriptorSetLayout.pHandle(),
            .pushConstantRangeCount = hasPushConstant ? 1u : 0u,
            .pPushConstantRanges = hasPushConstant ? &pushConstantRange : nullptr,
    };
    CALL_VK(vkCreatePipelineLayout, mContext->device(), &layoutDesc, nullptr,
            mPipelineLayout.pHandle());

    // Create compute pipeline
    const auto workGroupSize = mContext->getWorkGroupSize();
    const uint32_t specializationData[] = {workGroupSize, workGroupSize};
    const std::vector<VkSpecializationMapEntry> specializationMap = {
            // clang-format off
            // constantID, offset,               size
            {0, 0 * sizeof(uint32_t), sizeof(uint32_t)},
            {1, 1 * sizeof(uint32_t), sizeof(uint32_t)},
            // clang-format on
    };
    const VkSpecializationInfo specializationInfo = {
            .mapEntryCount = static_cast<uint32_t>(specializationMap.size()),
            .pMapEntries = specializationMap.data(),
            .dataSize = sizeof(specializationData),
            .pData = specializationData,
    };
    const VkComputePipelineCreateInfo pipelineDesc = {
            .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .stage =
                    {
                            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                            .module = shaderModule.handle(),
                            .pName = "main",
                            .pSpecializationInfo = &specializationInfo,
                    },
            .layout = mPipelineLayout.handle(),
    };
    CALL_VK(vkCreateComputePipelines, mContext->device(), VK_NULL_HANDLE, 1, &pipelineDesc, nullptr,
            mPipeline.pHandle());
    return true;
}

void ComputePipeline::recordComputeCommands(VkCommandBuffer cmd, const void* pushConstantData,
                                            const Image& inputImage, const Image& outputImage,
                                            const Buffer* uniformBuffer) {
    // Update descriptor sets with input and output images
    updateDescriptorSets(inputImage, outputImage, uniformBuffer);

    // Record compute pipeline
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeline.handle());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelineLayout.handle(), 0, 1,
                            &mDescriptorSet, 0, nullptr);
    if (pushConstantData != nullptr && mPushConstantSize > 0) {
        vkCmdPushConstants(cmd, mPipelineLayout.handle(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                           mPushConstantSize, pushConstantData);
    }
    const auto workGroupSize = mContext->getWorkGroupSize();
    const uint32_t groupCountX = ceilOfDiv(outputImage.width(), workGroupSize);
    const uint32_t groupCountY = ceilOfDiv(outputImage.height(), workGroupSize);
    vkCmdDispatch(cmd, groupCountX, groupCountY, 1);
}

}  // namespace sample
