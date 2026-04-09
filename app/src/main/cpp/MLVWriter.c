/*
 * MIT License
 *
 * Copyright (C) 2019 Ilia Sibiryakov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "MLVWriter.h"
#include "mlv_structs.h"

size_t sizeof_MLVWriter() { return sizeof(struct MLVWriter); }

void init_MLVWriter(MLVWriter_t * Writer, int Width, int Height, int BitDepth, int Compressed, int BlackLevel, int WhiteLevel, int FPSNumerator, int FPSDenominator, int CFA)
{
    memset(Writer, 0, sizeof(struct MLVWriter));

    memcpy(Writer->MLVI.block.fileMagic, "MLVI", 4);
    memcpy(Writer->MLVI.block.versionString, "v2.0", 4);
    Writer->MLVI.block.blockSize = sizeof(mlv_file_hdr_t);
    Writer->MLVI.write = 1;

    memcpy(Writer->RAWI.block.blockType, "RAWI", 4);
    Writer->RAWI.block.blockSize = sizeof(mlv_rawi_hdr_t);
    Writer->RAWI.write = 1;

    Writer->RAWI.block.xRes = Width;
    Writer->RAWI.block.yRes = Height;
    Writer->RAWI.block.raw_info.width = Width;
    Writer->RAWI.block.raw_info.height = Height;
    Writer->RAWI.block.raw_info.bits_per_pixel = BitDepth;
    Writer->RAWI.block.raw_info.black_level = BlackLevel;
    Writer->RAWI.block.raw_info.white_level = WhiteLevel;

    switch(CFA) {
        case 0: Writer->RAWI.block.raw_info.cfa_pattern = 0x02010100; break; // RGGB
        case 1: Writer->RAWI.block.raw_info.cfa_pattern = 0x01020001; break; // GRBG
        case 2: Writer->RAWI.block.raw_info.cfa_pattern = 0x01000201; break; // GBRG
        case 3: Writer->RAWI.block.raw_info.cfa_pattern = 0x00010102; break; // BGGR
        default: Writer->RAWI.block.raw_info.cfa_pattern = 0x02010100; break;
    }

    /* Video Class and compression */
    Writer->MLVI.block.videoClass = MLV_VIDEO_CLASS_RAW;
    if (Compressed) Writer->MLVI.block.videoClass |= MLV_VIDEO_CLASS_FLAG_LJ92;

    /* FPS */
    Writer->MLVI.block.sourceFpsNom = FPSNumerator;
    Writer->MLVI.block.sourceFpsDenom = FPSDenominator;

    /* Initialise static headers */
    memcpy(Writer->VIDF.blockType, "VIDF", 4);
    memcpy(Writer->EXPO.block.blockType, "EXPO", 4);
    Writer->EXPO.block.blockSize = sizeof(mlv_expo_hdr_t);
}

void MLVWriterSetCameraInfo(MLVWriter_t * Writer, char * CameraName, uint32_t CameraModelID, double * ColourMatrix)
{
    memcpy(Writer->IDNT.block.blockType, "IDNT", 4);
    Writer->IDNT.block.blockSize = sizeof(mlv_idnt_hdr_t);
    if (CameraName) strncpy((char*)Writer->IDNT.block.cameraName, CameraName, 32);

    if (ColourMatrix) {
        for (int i = 0; i < 9; ++i) {
            // Map to RAWI color matrix as per specification
            Writer->RAWI.block.raw_info.color_matrix1[i*2] = (int32_t)(ColourMatrix[i] * 10000.0);
            Writer->RAWI.block.raw_info.color_matrix1[i*2+1] = 10000;
        }
    }
    Writer->IDNT.write = 1;
}

size_t MLVWriterGetHeaderSize(MLVWriter_t * Writer)
{
    size_t size = 0;
    if (Writer->MLVI.write) size += Writer->MLVI.block.blockSize;
    if (Writer->RAWI.write) size += Writer->RAWI.block.blockSize;
    if (Writer->LENS.write) size += Writer->LENS.block.blockSize;
    if (Writer->IDNT.write) size += Writer->IDNT.block.blockSize;
    if (Writer->EXPO.write) size += Writer->EXPO.block.blockSize;
    if (Writer->WBAL.write) size += Writer->WBAL.block.blockSize;
    return size;
}

void MLVWriterGetHeaderData(MLVWriter_t * Writer, void * HeaderData, int NumFrames)
{
    uint8_t * p = (uint8_t *)HeaderData;
    Writer->MLVI.block.videoFrameCount = NumFrames;

#define COPY_BLK(Name) if(Writer->Name.write) { memcpy(p, &Writer->Name.block, Writer->Name.block.blockSize); p += Writer->Name.block.blockSize; }
    COPY_BLK(MLVI)
    COPY_BLK(RAWI)
    COPY_BLK(LENS)
    COPY_BLK(IDNT)
    COPY_BLK(EXPO)
    COPY_BLK(WBAL)
}

size_t MLVWriterGetFrameHeaderSize(MLVWriter_t * Writer) { return sizeof(mlv_vidf_hdr_t); }

void MLVWriterGetFrameHeaderData(MLVWriter_t * Writer, uint64_t FrameIndex, size_t FrameDataSize, void * FrameHeaderData)
{
    Writer->VIDF.blockSize = sizeof(mlv_vidf_hdr_t) + FrameDataSize;
    Writer->VIDF.frameNumber = (uint32_t)FrameIndex;
    memcpy(FrameHeaderData, &Writer->VIDF, sizeof(mlv_vidf_hdr_t));
}