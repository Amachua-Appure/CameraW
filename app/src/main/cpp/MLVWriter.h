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

#ifndef _MLVWriter_h_
#define _MLVWriter_h_

#include <stdint.h>


#pragma pack(push, 1)
#include "mlv_structs.h"
#pragma pack(pop)

#define MLVWriter_header_block(BlockType, BlockName) \
struct \
{ \
    union { \
        BlockType block; \
        mlv_hdr_t header; \
    }; \
    int write; \
} BlockName;

struct MLVWriter
{
    MLVWriter_header_block(mlv_file_hdr_t, MLVI)
    MLVWriter_header_block(mlv_rawi_hdr_t, RAWI)
    MLVWriter_header_block(mlv_wavi_hdr_t, WAVI)
    MLVWriter_header_block(mlv_expo_hdr_t, EXPO)
    MLVWriter_header_block(mlv_lens_hdr_t, LENS)
    MLVWriter_header_block(mlv_rtci_hdr_t, RTCI)
    MLVWriter_header_block(mlv_idnt_hdr_t, IDNT)
    MLVWriter_header_block(mlv_info_hdr_t, INFO)
    MLVWriter_header_block(mlv_diso_hdr_t, DISO)
    MLVWriter_header_block(mlv_mark_hdr_t, MARK)
    MLVWriter_header_block(mlv_styl_hdr_t, STYL)
    MLVWriter_header_block(mlv_elvl_hdr_t, ELVL)
    MLVWriter_header_block(mlv_wbal_hdr_t, WBAL)

    mlv_vidf_hdr_t VIDF;
    mlv_audf_hdr_t AUDF;
};

typedef struct MLVWriter MLVWriter_t;

size_t sizeof_MLVWriter();
void init_MLVWriter(MLVWriter_t * Writer, int Width, int Height, int BitDepth, int Compressed, int BlackLevel, int WhiteLevel, int FPSNumerator, int FPSDenominator, int cfa);
void MLVWriterSetCameraInfo(MLVWriter_t * Writer, char * CameraName, uint32_t CameraModelID, double * ColourMatrix);
size_t MLVWriterGetHeaderSize(MLVWriter_t * Writer);
size_t MLVWriterGetFrameHeaderSize(MLVWriter_t * Writer);
void MLVWriterGetFrameHeaderData(MLVWriter_t * Writer, uint64_t FrameIndex, size_t FrameDataSize, void * FrameHeaderData);
void MLVWriterGetHeaderData(MLVWriter_t * Writer, void * HeaderData, int NumFrames);

#endif