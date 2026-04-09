/*
lj92.h
(c) Andrew Baldwin 2014

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

#ifndef LJ92_H
#define LJ92_H

#include <stdint.h>

enum LJ92_ERRORS {
    LJ92_ERROR_NONE = 0,
    LJ92_ERROR_CORRUPT = -1,
    LJ92_ERROR_NO_MEMORY = -2,
    LJ92_ERROR_BAD_HANDLE = -3,
    LJ92_ERROR_TOO_WIDE = -4,
    LJ92_ERROR_ENCODER = -5
};

typedef struct _ljp* lj92;

int lj92_open(lj92* lj,
              uint8_t* data, int datalen,
              int* width, int* height, int* bitdepth, int* components);

void lj92_close(lj92 lj);

int lj92_decode(lj92 lj,
                uint16_t* target, int writeLength, int skipLength,
                uint16_t* linearize, int linearizeLength);

int lj92_encode(uint16_t* image, int width, int height, int bitdepth,
                int readLength, int skipLength,
                uint16_t* delinearize, int delinearizeLength,
                uint8_t** encoded, int* encodedLength);

int lj92_encode_direct(uint16_t* image, int width, int height, int bitdepth,
                       int readLength, int skipLength,
                       uint8_t* out_buffer, int out_capacity);


typedef struct _lje* lj92_encoder;

lj92_encoder lj92_encoder_init(int width, int height, int bitdepth,
                               int readLength, int skipLength);

int lj92_encode_stateful(lj92_encoder enc, uint16_t* image,
                         uint8_t* out_buffer, int out_capacity);

void lj92_encoder_close(lj92_encoder enc);

#endif