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

#pragma version(1)
#pragma rs java_package_name(com.android.example.rsmigration)
#pragma rs_fp_relaxed

#define MAX_KERNEL_SIZE 51

int32_t gWidth;
int32_t gHeight;
rs_allocation gScratch1;
rs_allocation gScratch2;

int32_t gRadius;
float gKernel[MAX_KERNEL_SIZE];

float4 RS_KERNEL copyIn(uchar4 in) {
    return convert_float4(in);
}

float4 RS_KERNEL horizontal(uint32_t x, uint32_t y) {
    float4 blurredPixel = 0;
    int i = 0;
    for (int r = -gRadius; r <= gRadius; r++) {
        // Make sure we do not have out of range index.
        int validX = clamp((int)x + r, (int)0, (int)(gWidth - 1));
        float4 pixel = rsGetElementAt_float4(gScratch1, validX, y);
        blurredPixel += pixel * gKernel[i++];
    }
    return blurredPixel;
}

uchar4 RS_KERNEL vertical(uint32_t x, uint32_t y) {
    float3 blurredPixel = 0;
    int i = 0;
    for (int r = -gRadius; r <= gRadius; r++) {
        // Make sure we do not have out of range index.
        int validY = clamp((int)y + r, (int)0, (int)(gHeight - 1));
        float4 pixel = rsGetElementAt_float4(gScratch2, x, validY);
        blurredPixel += pixel.rgb * gKernel[i++];
    }

    uchar4 out;
    out.rgb = convert_uchar3(clamp(blurredPixel, 0.f, 255.f));
    out.a = 0xff;
    return out;
}
