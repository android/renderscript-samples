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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.app.AppCompatActivity
import kotlin.system.measureNanoTime


class MainActivity : AppCompatActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName

        // We need two output images to avoid computing into an image currently on display.
        private const val NUMBER_OF_OUTPUT_IMAGES = 2

        // The maximum number of iterations to run for warmup.
        private const val MAX_WARMUP_ITERATIONS = 10

        // The maximum amount of time in ms to run for warmup.
        private const val MAX_WARMUP_TIME_MS = 1000.0

        // The maximum number of iterations to run during the benchmark.
        private const val MAX_BENCHMARK_ITERATIONS = 1000

        // The maximum amount of time in ms to run during the benchmark.
        private const val MAX_BENCHMARK_TIME_MS = 5000.0

        init {
            System.loadLibrary("rs_migration_jni")
        }
    }

    private lateinit var mLatencyTextView: TextView
    private lateinit var mImageView: ImageView
    private lateinit var mSeekBar: SeekBar
    private lateinit var mProcessorSpinner: Spinner
    private lateinit var mFilterSpinner: Spinner
    private lateinit var mBenchmarkButton: Button

    // Input and outputs
    private lateinit var mInputImage: Bitmap
    private var mCurrentOutputImageIndex = 0

    // Image processors
    private lateinit var mImageProcessors: Array<ImageProcessor>
    private lateinit var mCurrentProcessor: ImageProcessor

    enum class FilterMode {
        ROTATE_HUE, BLUR
    }

    private var mFilterMode = FilterMode.ROTATE_HUE

    private var mLatestThread: Thread? = null
    private val mLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load bitmap resources
        mInputImage = loadBitmap(R.drawable.data)

        // Set up image processors
        val imageProcessors = mutableListOf(
            // RenderScript intrinsics
            RenderScriptImageProcessor(this, useIntrinsic = true),
            // RenderScript script kernels
            RenderScriptImageProcessor(this, useIntrinsic = false),
            // Vulkan compute pipeline
            VulkanImageProcessor(this),
            // GLSL compute pipeline
            GLSLImageProcessor()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // RenderEffect
            imageProcessors.add(RenderEffectImageProcessor())
        }
        mImageProcessors = imageProcessors.toTypedArray()

        mImageProcessors.forEach { processor ->
            processor.configureInputAndOutput(mInputImage, NUMBER_OF_OUTPUT_IMAGES)
        }
        mCurrentProcessor = mImageProcessors[0]

        // Set up image view
        mImageView = findViewById(R.id.imageView)
        mLatencyTextView = findViewById(R.id.latencyText)

        // Set up seek bar
        mSeekBar = findViewById(R.id.seekBar)
        mSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                startUpdateImage(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        mSeekBar.progress = 50

        // Set up spinner for image processor selection
        mProcessorSpinner = findViewById(R.id.processorSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.processor_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mProcessorSpinner.adapter = adapter
        }
        mProcessorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                mCurrentProcessor = mImageProcessors[position]
                startUpdateImage(mSeekBar.progress)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set up spinner for filter mode selection
        mFilterSpinner = findViewById(R.id.filterSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.filter_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mFilterSpinner.adapter = adapter
        }
        mFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                mFilterMode = FilterMode.values()[position]
                startUpdateImage(mSeekBar.progress)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set up benchmark button
        mBenchmarkButton = findViewById(R.id.benchmarkButton)
        mBenchmarkButton.setOnClickListener { startBenchmark() }
    }

    override fun onDestroy() {
        mImageProcessors.forEach { processor -> processor.cleanup() }
        super.onDestroy()
    }

    // Helper method to load a Bitmap from resource
    private fun loadBitmap(resource: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inScaled = false
        return BitmapFactory.decodeResource(resources, resource, options)
            ?: throw RuntimeException("Unable to load bitmap.")
    }

    // Helper method to map the progress from [0, 100] to the given range [min, max].
    private fun rescale(progress: Int, min: Double, max: Double): Double {
        return (max - min) * (progress / 100.0) + min
    }

    // Run the filter once. This method will block the thread before it is finished.
    private fun runFilter(processor: ImageProcessor, filter: FilterMode, progress: Int): Bitmap {
        return when (filter) {
            FilterMode.ROTATE_HUE -> {
                val radian = rescale(progress, -Math.PI, Math.PI)
                processor.rotateHue(radian.toFloat(), mCurrentOutputImageIndex)
            }
            FilterMode.BLUR -> {
                val radius = rescale(progress, 1.0, 25.0)
                processor.blur(radius.toFloat(), mCurrentOutputImageIndex)
            }
        }
    }

    // Start a new thread to run the filter and update the image once it is finished.
    private fun startUpdateImage(progress: Int) {
        val filterMode = mFilterMode
        val processor = mCurrentProcessor

        // Start a new thread to invoke RenderScript/Vulkan kernels without blocking the UI thread.
        mLatestThread = Thread(Runnable {
            synchronized(mLock) {
                // If there is a new worker thread scheduled while this thread is waiting on the
                // lock, cancel this thread. This ensures that when worker threads are piled up
                // (typically in slow device with heavy kernel), only the latest (and already
                // started) thread invokes RenderScript/Vulkan operation.
                if (mLatestThread != Thread.currentThread()) {
                    return@Runnable
                }

                // Apply the filter and measure the latency.
                lateinit var bitmapOut: Bitmap
                val duration = measureNanoTime {
                    bitmapOut = runFilter(processor, filterMode, progress)
                }

                // Update image and text on UI thread.
                this@MainActivity.runOnUiThread {
                    mImageView.setImageBitmap(bitmapOut)
                    mLatencyTextView.text = getString(R.string.latency_text, duration / 1000000.0)
                }
                mCurrentOutputImageIndex = (mCurrentOutputImageIndex + 1) % NUMBER_OF_OUTPUT_IMAGES
            }
        })
        mLatestThread?.start()
    }

    // Run the benchmark, calculate the average latency in ms.
    // This method will block the thread before it is finished.
    private fun runBenchmark(processor: ImageProcessor, filter: FilterMode, progress: Int): Double {
        // Helper method to run a benchmark loop with the given iteration and duration limit.
        val runBenchmarkLoop = {maxIterations: Int, maxTimeMs: Double ->
            var iterations = 0
            var totalTime = 0.0
            while (iterations < maxIterations && totalTime < maxTimeMs) {
                iterations += 1
                totalTime += measureNanoTime { runFilter(processor, filter, progress) } / 1000000.0
            }
            totalTime / iterations
        }

        // Warmup runs
        runBenchmarkLoop(MAX_WARMUP_ITERATIONS, MAX_WARMUP_TIME_MS)

        // Actual benchmark runs
        val avgMs = runBenchmarkLoop(MAX_BENCHMARK_ITERATIONS, MAX_BENCHMARK_TIME_MS)

        // Report results in logcat
        val processorName = processor.name
        val filterName = filter.toString()
        Log.i(
            TAG,
            "Benchmark result: filter = ${filterName}, progress = ${progress}, " +
            "processor = ${processorName}, avg_ms = ${avgMs}"
        )
        return avgMs
    }

    // Start a new thread to run the benchmark and display the result once it is finished.
    private fun startBenchmark() {
        // Disable the widgets to ensure that during the benchmark:
        // - the setup {processor, filterMode, progress} will not change
        // - startUpdateImage will not be invoked
        mSeekBar.isEnabled = false
        mProcessorSpinner.isEnabled = false
        mFilterSpinner.isEnabled = false
        mBenchmarkButton.isEnabled = false
        mLatencyTextView.setText(R.string.benchmark_running_text)

        // Start a new thread to run benchmark without blocking the UI thread.
        Thread {
            synchronized(mLock) {
                // Run benchmark.
                val avgMs = runBenchmark(mCurrentProcessor, mFilterMode, mSeekBar.progress)

                // Display benchmark result and re-enable widgets.
                this@MainActivity.runOnUiThread {
                    mLatencyTextView.text = getString(R.string.benchmark_result_text, avgMs)
                    mSeekBar.isEnabled = true
                    mProcessorSpinner.isEnabled = true
                    mFilterSpinner.isEnabled = true
                    mBenchmarkButton.isEnabled = true
                }
            }
        }.start()
    }
}
