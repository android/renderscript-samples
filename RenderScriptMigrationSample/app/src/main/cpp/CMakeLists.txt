#
# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.10.2)
project(rs_migration_jni)

add_library(rs_migration_jni
        SHARED
        RsMigration_jni.cpp
        ComputePipeline.cpp
        ImageProcessor.cpp
        VulkanContext.cpp
        VulkanResources.cpp
        GLDebug.cpp)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++1z")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Weverything -Werror")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-c++98-compat-pedantic")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-c99-extensions")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-padded")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-switch-enum")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-missing-prototypes")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-sign-conversion")

find_library(libandroid android)
find_library(liblog log)
find_library(libjnigraphics jnigraphics)
find_library(libvulkan vulkan)
find_library(libgl GLESv3)
find_library(libegl EGL)
target_link_libraries(rs_migration_jni
        ${libandroid}
        ${liblog}
        ${libjnigraphics}
        ${libvulkan}
        ${libgl}
        ${libegl})
