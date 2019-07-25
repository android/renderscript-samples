/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.basicrenderscript;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.RenderScript;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

public class MainActivity extends AppCompatActivity {

    /**
     * Number of bitmaps that is used for RenderScript thread and UI thread synchronization.
     */
    private final int NUM_BITMAPS = 2;
    private int mCurrentBitmap = 0;
    private Bitmap mBitmapIn;
    private Bitmap[] mBitmapsOut;
    private ImageView mImageView;

    private Allocation mInAllocation;
    private Allocation[] mOutAllocations;
    private ScriptC_saturation mScript;
    private RenderScriptTask mCurrentTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main_layout);

        // Initialize UI
        mBitmapIn = loadBitmap(R.drawable.data);
        mBitmapsOut = new Bitmap[NUM_BITMAPS];
        for (int i = 0; i < NUM_BITMAPS; ++i) {
            mBitmapsOut[i] = Bitmap.createBitmap(mBitmapIn.getWidth(),
                    mBitmapIn.getHeight(), mBitmapIn.getConfig());
        }

        mImageView = (ImageView) findViewById(R.id.imageView);
        mImageView.setImageBitmap(mBitmapsOut[mCurrentBitmap]);
        mCurrentBitmap += (mCurrentBitmap + 1) % NUM_BITMAPS;

        SeekBar seekbar = (SeekBar) findViewById(R.id.seekBar1);
        seekbar.setProgress(50);
        seekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                float max = 2.0f;
                float min = 0.0f;
                float f = (float) ((max - min) * (progress / 100.0) + min);
                updateImage(f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Create renderScript
        createScript();

        // Invoke renderScript kernel and update imageView
        updateImage(1.0f);
    }

    /**
     * Initialize RenderScript.
     *
     * <p>In the sample, it creates RenderScript kernel that performs saturation manipulation.</p>
     */
    private void createScript() {
        // Initialize RS
        RenderScript rs = RenderScript.create(this);

        // Allocate buffers
        mInAllocation = Allocation.createFromBitmap(rs, mBitmapIn);
        mOutAllocations = new Allocation[NUM_BITMAPS];
        for (int i = 0; i < NUM_BITMAPS; ++i) {
            mOutAllocations[i] = Allocation.createFromBitmap(rs, mBitmapsOut[i]);
        }

        // Load script
        mScript = new ScriptC_saturation(rs);
    }

    /*
     * In the AsyncTask, it invokes RenderScript intrinsics to do a filtering.
     * After the filtering is done, an operation blocks at Allocation.copyTo() in AsyncTask thread.
     * Once all operation is finished at onPostExecute() in UI thread, it can invalidate and update
     * ImageView UI.
     */
    private class RenderScriptTask extends AsyncTask<Float, Void, Integer> {
        Boolean issued = false;

        protected Integer doInBackground(Float... values) {
            int index = -1;
            if (!isCancelled()) {
                issued = true;
                index = mCurrentBitmap;

                // Set global variable in RS
                mScript.set_saturationValue(values[0]);

                // Invoke saturation filter kernel
                mScript.forEach_saturation(mInAllocation, mOutAllocations[index]);

                // Copy to bitmap and invalidate image view
                mOutAllocations[index].copyTo(mBitmapsOut[index]);
                mCurrentBitmap = (mCurrentBitmap + 1) % NUM_BITMAPS;
            }
            return index;
        }

        void updateView(Integer result) {
            if (result != -1) {
                // Request UI update
                mImageView.setImageBitmap(mBitmapsOut[result]);
                mImageView.invalidate();
            }
        }

        protected void onPostExecute(Integer result) {
            updateView(result);
        }

        protected void onCancelled(Integer result) {
            if (issued) {
                updateView(result);
            }
        }
    }

    /**
     * Invoke AsyncTask and cancel previous task. When AsyncTasks are piled up (typically in slow
     * device with heavy kernel), Only the latest (and already started) task invokes RenderScript
     * operation.
     */
    private void updateImage(final float f) {
        if (mCurrentTask != null) {
            mCurrentTask.cancel(false);
        }
        mCurrentTask = new RenderScriptTask();
        mCurrentTask.execute(f);
    }

    /**
     * Helper to load Bitmap from resource
     */
    private Bitmap loadBitmap(int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(getResources(), resource, options);
    }

}
