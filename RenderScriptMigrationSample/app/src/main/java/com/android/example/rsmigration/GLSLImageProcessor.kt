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
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11
import android.opengl.GLES20
import android.opengl.GLES31
import android.opengl.GLES31Ext
import android.opengl.GLES32
import android.opengl.GLU
import android.opengl.GLUtils
import android.util.Log
import androidx.opengl.EGLImageKHR
import androidx.opengl.EGLSyncKHR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.collections.*
import java.lang.reflect.Modifier
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val LOG_TAG = "GLSLImageProcessor"

// turn this off for release, since it creates a bunch of extra JNI calls that aren't free
const val CHECK_GL_ERRORS = false

private val EGL_CONFIG_ATTRIBUTES = intArrayOf(
    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
    EGL14.EGL_RED_SIZE, 8,
    EGL14.EGL_GREEN_SIZE, 8,
    EGL14.EGL_BLUE_SIZE, 8,
    EGL14.EGL_ALPHA_SIZE, 8,
    EGL14.EGL_DEPTH_SIZE, 0,
    EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_NONE,
    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
    EGL14.EGL_NONE
)

fun checkGlErrorImpl() {
    var error: Int
    var noError = true
    while (run { error = GLES31.glGetError(); error } != GLES31.GL_NO_ERROR) {
        val method = Thread.currentThread().stackTrace[3].methodName
        val lineNumber = Thread.currentThread().stackTrace[3].lineNumber
        Log.d(
            "GL ERROR",
            "Error: " + error + " (" + GLU.gluErrorString(error) + "): " + method + " LN:" + lineNumber
        )
        noError = false
    }
    assert(noError)
}

// a simple block that checks for GL errors after the contents of the block are executed
inline fun <R> checkGLError(block: () -> R): R {
    val v = block()
    if (CHECK_GL_ERRORS) {
        checkGlErrorImpl()
    }
    return v
}

// Having workgroups are critical to get performance -- so critical that some GPU drivers will
// automatically create them for you in some cases. 8x8 workgroups give reasonable performance
// across multiple GPU families on these shaders, but be sure to test against your workloads.
private const val WORKGROUP_SIZE_X = 8
private const val WORKGROUP_SIZE_Y = 8

private const val ROTATION_MATRIX_SHADER =
    """#version 310 es
    layout(std430) buffer;
    layout (local_size_x = $WORKGROUP_SIZE_X, local_size_y = $WORKGROUP_SIZE_Y, local_size_z = 1) in;
        
    uniform layout (rgba8, binding = 0) readonly highp image2D u_inputImage;
    uniform layout (rgba8, binding = 1) writeonly highp image2D u_outputImage;
    uniform mat3 u_colorMatrix;

    void main() { 
        ivec2 texelCoord = ivec2(gl_GlobalInvocationID.xy);
        vec3 inputPixel = imageLoad(u_inputImage, texelCoord).rgb;
        vec3 resultPixel = u_colorMatrix * inputPixel;
        imageStore( u_outputImage, texelCoord, vec4(resultPixel, 1.0f));
    }        
"""

// We have two shaders for both horizontal and vertical blurring. This horizontal one uses a
// texture sampler and then places the output into a temporary image buffer.
private const val BLUR_HORIZONTAL_SHADER =
    """#version 310 es
    layout(std430) buffer;
    layout (local_size_x = $WORKGROUP_SIZE_X, local_size_y = $WORKGROUP_SIZE_Y, local_size_z = 1) in;
    uniform layout (binding = 0) sampler2D u_inputSampler;
    uniform layout (rgba8, binding = 4) writeonly highp image2D u_tempImage;
    layout (binding = 2) readonly buffer SSBO {
        // Tightly packed float elements (std430)
        float kernel[52];
    } ssbo;
    uniform int radius;

    void main() {
        vec4 blurredPixel = vec4(0.0, 0.0, 0.0, 1.0);
        ivec2 tSize = textureSize(u_inputSampler, 0);
        for (int r = -radius; r <= radius; ++r) {
            // We do not need to manually clamp to edge here because we have specified
            // GL_CLAMP_TO_EDGE when creating the sampler.
            int kernelIndex = r + radius;
            vec2 coord = vec2(int(gl_GlobalInvocationID.x) + r, float(gl_GlobalInvocationID.y));
            // convert from standard coordinates to texture coordinates by dividing by the
            // size of the texture
            coord /= vec2(tSize);
            vec3 pixel = texture(u_inputSampler, coord).rgb;
            blurredPixel.rgb += ssbo.kernel[kernelIndex] * pixel;
        }
        imageStore(u_tempImage, ivec2(gl_GlobalInvocationID.xy), blurredPixel);
    }
"""

private const val BLUR_VERTICAL_SHADER =
    """#version 310 es
    layout(std430) buffer;
    layout (local_size_x = $WORKGROUP_SIZE_X, local_size_y = $WORKGROUP_SIZE_Y, local_size_z = 1) in;

    uniform layout (rgba8, binding = 4) readonly highp image2D u_tempImage;
    uniform layout (rgba8, binding = 1) writeonly highp image2D u_outputImage;
    layout (binding = 2) readonly buffer SSBO {
        // Tightly packed float elements (std430)
        float kernel[52];
    } ssbo;
    uniform int radius;

    void main() {
        vec4 blurredPixel = vec4(0.0, 0.0, 0.0, 1.0);
        ivec2 iSize = imageSize(u_tempImage);
        for (int r = -radius; r <= radius; ++r) {
            ivec2 coord = ivec2(int(gl_GlobalInvocationID.x), clamp((int(gl_GlobalInvocationID.y) + r),0,iSize.y-1));
            vec3 pixel = imageLoad(u_tempImage, coord).rgb;
            int kernelIndex = r + radius;
            blurredPixel.rgb += ssbo.kernel[kernelIndex] * pixel;
        }
        
        imageStore(u_outputImage, ivec2(gl_GlobalInvocationID.xy), blurredPixel);
    }
"""

private const val EGL_SURFACE_WIDTH = 1
private const val EGL_SURFACE_HEIGHT = 1
private const val INPUT_TEXTURE_UNIT_INDEX = 0
private const val OUTPUT_TEXTURE_UNIT_INDEX = 1
private const val TEMP_TEXTURE_UNIT_INDEX = 4

class GLSLImageProcessor : ImageProcessor {
    override val name = "GLSL"
    private val mGLThreadExecutor = Executors.newSingleThreadExecutor()
    private val mGLScope = CoroutineScope(mGLThreadExecutor.asCoroutineDispatcher())
    private var mSurface: EGLSurface? = null

    // Processor globals from initializing EGL
    private val mDisplay = getDefaultDisplay()
    private val mConfig = chooseEGLConfig(mDisplay!!)
    private val mContext = createEGLContext(mDisplay!!, mConfig!!)

    // used to store our source image texture
    private lateinit var mInputImage: Bitmap

    // textures for the two different sets of compute examples
    // blur requires a sampler, since it leverages sub-pixel samples to work, so we bind it separately
    private val mRotateTextureHandle = IntArray(1)
    private val mBlurTextureHandle = IntArray(1)
    private val mOutputTextureHandle = IntArray(1)
    private val mTempTextureHandle = IntArray(1)

    // programs
    private var mRotateProgram: Int = -1
    private var mBlurHorizontalProgram: Int = -1
    private var mBlurVerticalProgram: Int = -1

    // output
    private lateinit var mOffscreenBufferHandle: IntArray
    private lateinit var mOutputBuffers: Array<HardwareBuffer>
    private lateinit var mOutputEGLImages: Array<EGLImageKHR>
    private lateinit var mHardwareTextureHandle: IntArray

    private fun getDefaultDisplay(): EGLDisplay? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            ?: throw java.lang.RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        val eglInitialized = EGL14.eglInitialize(
            display,
            version,  /* majorOffset= */
            0,
            version,  /* minorOffset= */
            1
        )
        if (!eglInitialized) {
            throw java.lang.RuntimeException("eglInitialize failed")
        }
        return display
    }

    private fun chooseEGLConfig(display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val success = EGL14.eglChooseConfig(
            display,
            EGL_CONFIG_ATTRIBUTES,  /* attrib_listOffset= */
            0,
            configs,  /* configsOffset= */
            0,  /* config_size= */
            1,
            numConfigs,  /* num_configOffset= */
            0
        )
        if (!success || numConfigs[0] <= 0 || configs[0] == null) {
            throw java.lang.RuntimeException(
                "eglChooseConfig failed"
            )
        }
        return configs[0]
    }

    private fun createEGLContext(
        display: EGLDisplay,
        config: EGLConfig
    ): EGLContext? {
        val glAttributes = intArrayOf(
            EGL15.EGL_CONTEXT_MAJOR_VERSION, 3,
            EGL15.EGL_CONTEXT_MINOR_VERSION, 2,
            EGL15.EGL_CONTEXT_OPENGL_DEBUG, EGL14.EGL_TRUE,
            EGLExt.EGL_CONTEXT_FLAGS_KHR, EGL14.EGL_TRUE,
            EGL14.EGL_NONE
        )
        return EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, glAttributes, 0
        )
            ?: throw java.lang.RuntimeException("eglCreateContext failed")
    }

    private fun setTextureParameters() {
        // Set texture parameters
        checkGLError {
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MAG_FILTER,
                GLES31.GL_NEAREST
            )
        }
        checkGLError {
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MIN_FILTER,
                GLES31.GL_NEAREST
            )
        }
        checkGLError {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
        }
        checkGLError {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        }
    }

    private fun initializeRotateCompute() {
        // Let's create our source and target textures.
        mRotateProgram = checkGLError { GLES31.glCreateProgram() }
        checkGLError { initializeComputeShader(ROTATION_MATRIX_SHADER, mRotateProgram) }
        // we've got a shader!
        checkGLError { GLES31.glUseProgram(mRotateProgram) }
        val inputImageUniformLocation =
            checkGLError { GLES31.glGetUniformLocation(mRotateProgram, "u_inputImage") }
        if (inputImageUniformLocation == -1) {
            throw java.lang.RuntimeException("Uniform not found")
        }
        if (!GLES31.glIsProgram(mRotateProgram)) {
            throw java.lang.RuntimeException("Program doesn't exist")
        }

        // Create our texture handles
        checkGLError { GLES31.glGenTextures(1, mRotateTextureHandle, 0) }

        // Load the image associated with our texture handle
        checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mRotateTextureHandle[0]) }
        checkGLError {
            GLES31.glTexStorage2D(
                GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8,
                mInputImage.width, mInputImage.height
            )
        }
        checkGLError {
            GLUtils.texSubImage2D(
                GLES31.GL_TEXTURE_2D, 0, 0, 0,
                mInputImage
            )
        }
        checkGLError { GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + INPUT_TEXTURE_UNIT_INDEX) }

        // Load our source texture
        if (mRotateTextureHandle[0] != 0) {
            // Bind our source as a 2D shader image
            checkGLError {
                GLES31.glBindImageTexture(
                    INPUT_TEXTURE_UNIT_INDEX,
                    mRotateTextureHandle[0],
                    0,
                    false,
                    0,
                    GLES31.GL_READ_ONLY,
                    GLES31.GL_RGBA8
                )
            }
        }
    }

    private fun initializeComputeShader(shaderSource: String, program: Int): Int {
        val shader = checkGLError { GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER) }
        checkGLError { GLES31.glShaderSource(shader, shaderSource) }
        checkGLError { GLES31.glCompileShader(shader) }
        val rvalue = IntBuffer.allocate(1)
        checkGLError { GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, rvalue) }
        if (rvalue[0] == 0) {
            Log.d(LOG_TAG, GLES31.glGetShaderInfoLog(shader))
        }
        checkGLError { GLES31.glAttachShader(program, shader) }
        checkGLError { GLES31.glLinkProgram(program) }
        checkGLError { GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, rvalue) }
        if (rvalue[0] == 0) {
            Log.d(LOG_TAG, GLES31.glGetProgramInfoLog(program))
        }
        return shader
    }

    private fun initializeBlurCompute() {
        mBlurHorizontalProgram = checkGLError { GLES31.glCreateProgram() }
        initializeComputeShader(BLUR_HORIZONTAL_SHADER, mBlurHorizontalProgram)
        mBlurVerticalProgram = checkGLError { GLES31.glCreateProgram() }
        initializeComputeShader(BLUR_VERTICAL_SHADER, mBlurVerticalProgram)

        // we've got a shader!
        checkGLError { GLES31.glUseProgram(mBlurHorizontalProgram) }
        val inputImageUniformLocation =
            checkGLError { GLES31.glGetUniformLocation(mBlurHorizontalProgram, "u_inputSampler") }
        if (inputImageUniformLocation == -1) {
            throw java.lang.RuntimeException("Uniform not found")
        }
        if (!GLES31.glIsProgram(mBlurHorizontalProgram)) {
            throw java.lang.RuntimeException("Program doesn't exist")
        }
        val imageUnitIndex = 0

        // Create our texture handles
        checkGLError { GLES31.glGenTextures(1, mBlurTextureHandle, 0) }
        checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mBlurTextureHandle[0]) }
        // set a bunch of default parameters that are useful for image processing on our
        // bound GL_TEXTURE_2D
        setTextureParameters()
        if (inputImageUniformLocation != -1) {
            checkGLError { GLES31.glUniform1i(inputImageUniformLocation, imageUnitIndex) }
            checkGLError { GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, mInputImage, 0) }
        }
    }

    private fun createOffscreenSurface(
        display: EGLDisplay, config: EGLConfig, context: EGLContext, width: Int, height: Int
    ): EGLSurface? {
        val surface: EGLSurface?
        val pbufferAttributes = intArrayOf(
            EGL14.EGL_WIDTH,
            width,
            EGL14.EGL_HEIGHT,
            height,
            EGL14.EGL_NONE
        )
        surface =
            EGL14.eglCreatePbufferSurface(display, config, pbufferAttributes,  /* offset= */0)
        if (surface == null) {
            throw java.lang.RuntimeException("eglCreatePbufferSurface failed")
        }
        val eglMadeCurrent =
            EGL14.eglMakeCurrent(display,  /* draw= */surface,  /* read= */surface, context)
        if (!eglMadeCurrent) {
            throw RuntimeException("eglMakeCurrent failed")
        }
        return surface
    }

    private fun createHardwareOutputBuffer(width: Int, height: Int): HardwareBuffer {
        return HardwareBuffer.create(
            width, height, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_CPU_READ_RARELY or
//                HardwareBuffer.USAGE_CPU_WRITE_NEVER or
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
    }

    private fun initializeShaderInputsAndOutputs(numberOfOutputImages: Int) {
        // get output EGL Images
        mOutputEGLImages = Array(numberOfOutputImages) { i ->
            androidx.opengl.EGLExt.eglCreateImageFromHardwareBuffer(
                mDisplay!!,
                mOutputBuffers[i]
            )!!
        }

        checkGLError { GLES31.glGenTextures(1, mOutputTextureHandle, 0) }
        checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mOutputTextureHandle[0]) }
        checkGLError {
            GLES31.glTexStorage2D(
                GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8,
                mInputImage.width, mInputImage.height
            )
        }
        checkGLError { GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + OUTPUT_TEXTURE_UNIT_INDEX) }
        // Load our destination texture
        if (mOutputTextureHandle[0] != 0) {
            // Bind our destination as a 2D shader image
            checkGLError {
                GLES31.glBindImageTexture(
                    OUTPUT_TEXTURE_UNIT_INDEX,
                    mOutputTextureHandle[0],
                    0,
                    false,
                    0,
                    GLES31.GL_WRITE_ONLY,
                    GLES31.GL_RGBA8
                )
            }
        }

        checkGLError { GLES31.glGenTextures(1, mTempTextureHandle, 0) }
        checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mTempTextureHandle[0]) }
        checkGLError {
            GLES31.glTexStorage2D(
                GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8,
                mInputImage.width, mInputImage.height
            )
        }
        checkGLError { GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + TEMP_TEXTURE_UNIT_INDEX) }
        // temporary texture gets assigned to alternate texture unit
        if (mTempTextureHandle[0] != 0) {
            // Bind our destination as a 2D shader image
            checkGLError {
                GLES31.glBindImageTexture(
                    TEMP_TEXTURE_UNIT_INDEX,
                    mTempTextureHandle[0],
                    0,
                    false,
                    0,
                    GLES31.GL_READ_WRITE,
                    GLES31.GL_RGBA8
                )
            }
        }
        initializeRotateCompute()
        initializeBlurCompute()
        mHardwareTextureHandle = IntArray(numberOfOutputImages)
        checkGLError { GLES31.glGenTextures(numberOfOutputImages, mHardwareTextureHandle, 0) }

        // Create a handle to a new off screen frame buffer.
        mOffscreenBufferHandle =
            IntArray(numberOfOutputImages) { bindOffscreenBuffer(mOutputTextureHandle[0]) }
    }

    // This function sets up the input, which is shared between two of the shaders, as well
    // the shared output image texture.
    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        if (numberOfOutputImages <= 0) {
            throw RuntimeException("Invalid number of output images: $numberOfOutputImages")
        }
        mInputImage = inputImage
        val width = inputImage.width
        val height = inputImage.height
        mOutputBuffers = Array(numberOfOutputImages) { createHardwareOutputBuffer(width, height) }

        // we also use this to create our GL context, which we want to have on another thread
        val job = mGLScope.launch {
            // create an offscreen surface -- by default this is 1x1
            // since we're just going to use compute anyhow
            mSurface = createOffscreenSurface(
                mDisplay!!, mConfig!!, mContext!!,
                EGL_SURFACE_WIDTH, EGL_SURFACE_HEIGHT
            )
            if (mSurface == null) {
                throw java.lang.RuntimeException("create offscreen surface failed")
            }

            checkGLError { GLES31.glEnable(GLES31Ext.GL_DEBUG_OUTPUT_SYNCHRONOUS_KHR) }
            // The only bit of NDK code here, and it's not necessary to run it. (but, when you're
            // debugging, it's critical)
            EnableDebugLogging { source: Int, type: Int, _: Int, severity: Int, message: String ->
                val source_str = when (source) {
                    GLES32.GL_DEBUG_SOURCE_API -> "api"
                    GLES32.GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "window system"
                    GLES32.GL_DEBUG_SOURCE_SHADER_COMPILER -> "shader compiler"
                    GLES32.GL_DEBUG_SOURCE_THIRD_PARTY -> "third party"
                    GLES32.GL_DEBUG_SOURCE_APPLICATION -> "application"
                    else -> "other"
                }
                val type_str = when (type) {
                    GLES32.GL_DEBUG_TYPE_ERROR -> "error"
                    GLES32.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "deprecated behavior"
                    GLES32.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "undefined behavior"
                    GLES32.GL_DEBUG_TYPE_PORTABILITY -> "portability"
                    GLES32.GL_DEBUG_TYPE_PERFORMANCE -> "performance"
                    else -> "other"
                }
                val severity_str = when (severity) {
                    GLES32.GL_DEBUG_SEVERITY_HIGH -> "high"
                    GLES32.GL_DEBUG_SEVERITY_MEDIUM -> "medium"
                    GLES32.GL_DEBUG_SEVERITY_LOW -> "low"
                    else -> "notification"
                }
                Log.e(
                    LOG_TAG,
                    "source [$source_str], type [$type_str], severity [$severity_str], message [$message]"
                )
            }
            initializeShaderInputsAndOutputs(numberOfOutputImages)
        }
        // once all of our GL calls are queued we want to return on the image processor thread
        runBlocking {
            job.join()
        }
    }

    // This sample uses an offscreen buffer to read pixels from the image texture that is written
    // to by the shader.
    private fun bindOffscreenBuffer(texture: Int): Int {
        // Used for getting results back from OpenGL.
        val fboHandles = IntArray(1)

        // Create a handle to a new off screen frame buffer.
        checkGLError { GLES20.glGenFramebuffers(1, fboHandles, 0) }
        val newBufferId = fboHandles[0]
        // Bind the new frame buffer to GL_FRAMEBUFFER, which is the default one to read and write from,
        // though it can also be referred to by name.
        checkGLError { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, newBufferId) }

        // Attach the new texture to the off screen frame buffer (bound to GL_FRAMEBUFFER).
        checkGLError {
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texture,
                0
            )
        }
        val frameBufferStatus =
            checkGLError { GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) }
        check(frameBufferStatus == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Could not initialize off screen frame buffer, status was $frameBufferStatus."
        }
        return fboHandles[0]
    }

    // An extension function that gets the length of the strings returned in our byte array
    private fun ByteArray.strlen(): Int {
        for (i in this.indices) {
            if (this[i] == '\u0000'.code.toByte()) {
                return i
            }
        }
        return this.size
    }

    // bind the hardware texture handle, the EGL image into that handle, the framebuffer
    // that is tied to our shader output texture, and then copy from that framebuffer
    // into our output texture handle
    fun copyPixelsToHardwareBuffer(width: Int, height: Int, outputIndex: Int) {
        // bind the hardware texture handle, the EGL image into that handle, the framebuffer
        // that is tied to our shader output texture, and then copy from that framebuffer
        // into our output texture handle
        checkGLError {
            GLES31.glBindFramebuffer(
                GLES31.GL_FRAMEBUFFER,
                mOffscreenBufferHandle[outputIndex]
            )
        }
        checkGLError {
            GLES31.glBindTexture(
                GLES31.GL_TEXTURE_2D,
                mHardwareTextureHandle[outputIndex]
            )
        }
        checkGLError {
            androidx.opengl.EGLExt.glEGLImageTargetTexture2DOES(
                GLES31.GL_TEXTURE_2D,
                mOutputEGLImages[outputIndex]
            )
        }
        checkGLError {
            GLES31.glCopyTexSubImage2D(
                GLES31.GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                0,
                width,
                height
            )
        }
        checkGLError { GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0) }
        checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0) }
        checkGLError { GLES31.glUseProgram(0) }
        checkGLError { GLES31.glFinish() }
    }

    private fun roundUp(base: Int, divisor: Int): Int {
        return ceil(base.toDouble() / divisor.toDouble()).roundToInt()
    }

    // We calculate the rotation matrix and feed it in as a matrix uniform.
    override fun rotateHue(radian: Float, outputIndex: Int): Bitmap {
        val width = mInputImage.width
        val height = mInputImage.height
        val job = mGLScope.launch {
            checkGLError { GLES31.glUseProgram(mRotateProgram) }
            // Set HUE rotation matrix
            // The matrix below performs a combined operation of,
            // RGB->HSV transform * HUE rotation * HSV->RGB transform
            val cos = cos(radian.toDouble())
            val sin = sin(radian.toDouble())

            // GLES takes floatArrays from managed code and converts them to matrices
            val mat = floatArrayOf(
                // row 0
                (.299 + .701 * cos + .168 * sin).toFloat(),
                (.299 - .299 * cos - .328 * sin).toFloat(),
                (.299 - .300 * cos + 1.25 * sin).toFloat(),

                // row 1
                (.587 - .587 * cos + .330 * sin).toFloat(),
                (.587 + .413 * cos + .035 * sin).toFloat(),
                (.587 - .588 * cos - 1.05 * sin).toFloat(),

                // row 2
                (.114 - .114 * cos - .497 * sin).toFloat(),
                (.114 - .114 * cos + .292 * sin).toFloat(),
                (.114 + .886 * cos - .203 * sin).toFloat()
            )
            val inputMatrixUniformLocation =
                checkGLError { GLES31.glGetUniformLocation(mRotateProgram, "u_colorMatrix") }
            checkGLError { GLES31.glUniformMatrix3fv(inputMatrixUniformLocation, 1, false, mat, 0) }
            checkGLError {
                GLES31.glDispatchCompute(
                    roundUp(mInputImage.width, WORKGROUP_SIZE_X),
                    roundUp(mInputImage.height, WORKGROUP_SIZE_Y),
                    1
                )
            }
            checkGLError { GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) }

            copyPixelsToHardwareBuffer(width, height, outputIndex)
        }
        // since the image processor framework requires that we block
        runBlocking {
            job.join()
        }
        return Bitmap.wrapHardwareBuffer(
            mOutputBuffers[outputIndex],
            ColorSpace.get(ColorSpace.Named.SRGB)
        )!!
    }

    val mBlurData = FloatBuffer.allocate(52)
    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        val width = mInputImage.width
        val height = mInputImage.height
        mBlurData.rewind()
        if (radius < 1.0f || radius > 25.0f) {
            throw RuntimeException("Invalid radius ${radius}, must be within [1.0, 25.0]")
        }
        // Calculate gaussian kernel, this is equivalent to ComputeGaussianWeights at
        // https://cs.android.com/android/platform/superproject/+/master:frameworks/rs/cpu_ref/rsCpuIntrinsicBlur.cpp;l=57
        val e = 2.718281828459045f
        val pi = 3.1415926535897932f
        val sigma = 0.4f * radius + 0.6f
        val coeff1 = (1.0f / (sqrt(2.0 * pi) * sigma)).toFloat()
        val coeff2 = -1.0f / (2.0f * sigma * sigma)
        val iRadius = (ceil(radius.toDouble())).toInt()
        var normalizeFactor = 0.0f
        for (r in -iRadius..iRadius) {
            val value = coeff1 * e.pow(coeff2 * (r * r))
            mBlurData.put(r + iRadius, value)
            normalizeFactor += value;
        }
        normalizeFactor = 1.0f / normalizeFactor
        for (r in -iRadius..iRadius) {
            mBlurData.put(r + iRadius, mBlurData.get(r + iRadius) * normalizeFactor)
        }
        val job = mGLScope.launch {
            checkGLError { GLES31.glUseProgram(mBlurHorizontalProgram) }
            val radiusLocation =
                checkGLError { GLES31.glGetUniformLocation(mBlurHorizontalProgram, "radius") }
            val inputImageUniformLocation = checkGLError {
                GLES31.glGetUniformLocation(
                    mBlurHorizontalProgram,
                    "u_inputSampler"
                )
            }
            val imageUnitIndex = 0
            // rebind our input sampler
            checkGLError { GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mBlurTextureHandle[0]) }
            // set a bunch of default parameters that are useful for image processing on our
            // bound GL_TEXTURE_2D
            setTextureParameters()
            if (inputImageUniformLocation != -1) {
                checkGLError { GLES31.glUniform1i(inputImageUniformLocation, imageUnitIndex) }
                checkGLError { GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, mInputImage, 0) }
            }

            val buffer = IntArray(1)
            val numGroupsX = roundUp(mInputImage.width, WORKGROUP_SIZE_X)
            val numGroupsY = roundUp(mInputImage.height, WORKGROUP_SIZE_Y)
            checkGLError { GLES31.glGenBuffers(1, buffer, 0) }
            checkGLError { GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0]) }
            checkGLError {
                GLES31.glBufferData(
                    GLES31.GL_SHADER_STORAGE_BUFFER,
                    mBlurData.capacity() * 4,
                    mBlurData,
                    GLES31.GL_STREAM_READ
                )
            }
            checkGLError { GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buffer[0]) }
            checkGLError { GLES31.glUniform1i(radiusLocation, iRadius) }
            checkGLError { GLES31.glDispatchCompute(numGroupsX, numGroupsY, 1) }
            checkGLError { GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) }

            checkGLError { GLES31.glUseProgram(mBlurVerticalProgram) }
            checkGLError { GLES31.glUseProgram(mBlurVerticalProgram) }
            checkGLError { GLES31.glUniform1i(radiusLocation, iRadius) }
            checkGLError { GLES31.glDispatchCompute(numGroupsX, numGroupsY, 1) }
            checkGLError { GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) }

            copyPixelsToHardwareBuffer(width, height, outputIndex)
        }
        runBlocking {
            job.join()
        }
        return Bitmap.wrapHardwareBuffer(
            mOutputBuffers[outputIndex],
            ColorSpace.get(ColorSpace.Named.SRGB)
        )!!
    }

    override fun cleanup() {
        // destroy the EGL images
        mGLScope.launch {
            for (i in 0 until mOutputEGLImages.size) {
                androidx.opengl.EGLExt.eglDestroyImageKHR(mDisplay!!, mOutputEGLImages[i])
            }
        }
    }
}

// currently, this requires a bit of native code. it can be removed for release
external fun EnableDebugLogging(callback: GLES31Ext.DebugProcKHR): Boolean

