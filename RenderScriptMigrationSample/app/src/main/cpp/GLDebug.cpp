/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <android/log.h>
#include <jni.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>

// our JNICallback struct.
static thread_local struct JNICallback {
    JNIEnv *env;
    jmethodID mid;
    jobject obj;
} jniCallback;

static void openGLMessageCallback(GLenum source, GLenum type, GLuint id,
                                   GLenum severity, GLsizei, const GLchar* message,
                                   const void* userParam)
{
    if ( nullptr != userParam ) {
        const JNICallback* callback = reinterpret_cast<const JNICallback*>(userParam);

        jstring jniMessageString = callback->env->NewStringUTF(message);
        callback->env->CallVoidMethod(callback->obj, callback->mid,
                                            static_cast<jint>(source),
                                            static_cast<jint>(type),
                                            static_cast<jint>(id),
                                            static_cast<jint>(severity),
                                            jniMessageString );
    }
}

// There's no way to do this in managed code, so here's something to help out those devs that
// want some more debug info.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_android_example_rsmigration_GLSLImageProcessorKt_EnableDebugLogging(JNIEnv *env,
                                                                             jclass,
                                                                             jobject callback) {
    if ( env ) {
        auto debugCallback  = reinterpret_cast<void (*)(void *, void *)>(eglGetProcAddress("glDebugMessageCallback"));
        if (debugCallback) {
            // enable debug output
            glEnable(GL_DEBUG_OUTPUT);
            // call back on the same thread
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);

            jniCallback.env = env;
            jclass cls = env->GetObjectClass(callback);
            jniCallback.mid = env->GetMethodID(cls, "onMessage","(IIIILjava/lang/String;)V");
            jniCallback.obj = env->NewWeakGlobalRef(callback);
            debugCallback(reinterpret_cast<void*>(openGLMessageCallback), &jniCallback);
        }
    }
    return false;
}
