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

static rs_matrix3x3 ColorMatrix;

void init() {
    rsMatrixLoadIdentity(&ColorMatrix);
}

void setMatrix(rs_matrix3x3 m) {
    ColorMatrix = m;
}

uchar4 RS_KERNEL root(uchar4 in) {
    float4 color = convert_float4(in);
    color.rgb = rsMatrixMultiply(&ColorMatrix, color.rgb);
    color.rgb = clamp(color.rgb, 0.f, 255.f);
    return convert_uchar4(color);
}
