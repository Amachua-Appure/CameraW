/*
lj92.c
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

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lj92.h"

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;

typedef struct _ljp {
    u8* data;
    u8* dataend;
    int datalen;
    int scanstart;
    int ix;
    int x;
    int y;
    int bits;
    int components;
    int writelen;
    int skiplen;
    u16* linearize;
    int linlen;
#ifdef SLOW_HUFF
    int* maxcode;
    int* mincode;
    int* valptr;
    u8* huffval;
    int* huffsize;
    int* huffcode;
#else
    u16* hufflut;
    int huffbits;
#endif
    int cnt;
    u32 b;
    u16* image;
    u16* rowcache;
    u16* outrow[2];
} ljp;

static int find(ljp* self) {
    int ix = self->ix;
    u8* data = self->data;
    while (data[ix] != 0xFF && ix<(self->datalen-1)) {
        ix += 1;
    }
    ix += 2;
    if (ix>=self->datalen) return -1;
    self->ix = ix;
    return data[ix-1];
}

#define BEH(ptr) ((((int)(*&ptr))<<8)|(*(&ptr+1)))

static int parseHuff(ljp* self) {
    int ret = LJ92_ERROR_CORRUPT;
    u8* huffhead = &self->data[self->ix];
    u8* bits = &huffhead[2];
    bits[0] = 0;
    int hufflen = BEH(huffhead[0]);
    if ((self->ix + hufflen) >= self->datalen) return ret;
#ifdef SLOW_HUFF
    u8* huffval = calloc(hufflen - 19,sizeof(u8));
    if (huffval == NULL) return LJ92_ERROR_NO_MEMORY;
    self->huffval = huffval;
    for (int hix=0;hix<(hufflen-19);hix++) {
        huffval[hix] = self->data[self->ix+19+hix];
    }
    self->ix += hufflen;
    int k = 0;
    int i = 1;
    int j = 1;
    int huffsize_needed = 1;
    while (i<=16) {
        while (j<=bits[i]) {
            huffsize_needed++;
            k = k+1;
            j = j+1;
        }
        i = i+1;
        j = 1;
    }
    int* huffsize = calloc(huffsize_needed,sizeof(int));
    if (huffsize == NULL) return LJ92_ERROR_NO_MEMORY;
    self->huffsize = huffsize;
    k = 0;
    i = 1;
    j = 1;
    int hsix = 0;
    while (i<=16) {
        while (j<=bits[i]) {
            huffsize[hsix++] = i;
            k = k+1;
            j = j+1;
        }
        i = i+1;
        j = 1;
    }
    huffsize[hsix++] = 0;
    int huffcode_needed = 0;
    k = 0;
    int code = 0;
    int si = huffsize[0];
    while (1) {
        while (huffsize[k] == si) {
            huffcode_needed++;
            code = code+1;
            k = k+1;
        }
        if (huffsize[k] == 0)
            break;
        while (huffsize[k] != si) {
            code = code << 1;
            si = si + 1;
        }
    }
    int* huffcode = calloc(huffcode_needed,sizeof(int));
    if (huffcode == NULL) return LJ92_ERROR_NO_MEMORY;
    self->huffcode = huffcode;
    int hcix = 0;
    k = 0;
    code = 0;
    si = huffsize[0];
    while (1) {
        while (huffsize[k] == si) {
            huffcode[hcix++] = code;
            code = code+1;
            k = k+1;
        }
        if (huffsize[k] == 0)
            break;
        while (huffsize[k] != si) {
            code = code << 1;
            si = si + 1;
        }
    }
    i = 0;
    j = 0;
    int* maxcode = calloc(17,sizeof(int));
    if (maxcode == NULL) return LJ92_ERROR_NO_MEMORY;
    self->maxcode = maxcode;
    int* mincode = calloc(17,sizeof(int));
    if (mincode == NULL) return LJ92_ERROR_NO_MEMORY;
    self->mincode = mincode;
    int* valptr = calloc(17,sizeof(int));
    if (valptr == NULL) return LJ92_ERROR_NO_MEMORY;
    self->valptr = valptr;
    while (1) {
        while (1) {
            i++;
            if (i>16)
                break;
            if (bits[i]!=0)
                break;
            maxcode[i] = -1;
        }
        if (i>16)
            break;
        valptr[i] = j;
        mincode[i] = huffcode[j];
        j = j+bits[i]-1;
        maxcode[i] = huffcode[j];
        j++;
    }
    free(huffsize);
    self->huffsize = NULL;
    free(huffcode);
    self->huffcode = NULL;
    ret = LJ92_ERROR_NONE;
#else
    u8* huffvals = &self->data[self->ix+19];
    int maxbits = 16;
    while (maxbits>0) {
        if (bits[maxbits]) break;
        maxbits--;
    }
    self->huffbits = maxbits;
    u16* hufflut = malloc((1<<maxbits) * sizeof(u16));
    if (hufflut == NULL) return LJ92_ERROR_NO_MEMORY;
    self->hufflut = hufflut;
    int i = 0;
    int hv = 0;
    int rv = 0;
    int vl = 0;
    int hcode;
    int bitsused = 1;
    while (i<1<<maxbits) {
        if (bitsused>maxbits) {
            break;
        }
        if (vl >= bits[bitsused]) {
            bitsused++;
            vl = 0;
            continue;
        }
        if (rv == 1 << (maxbits-bitsused)) {
            rv = 0;
            vl++;
            hv++;
            continue;
        }
        hcode = huffvals[hv];
        hufflut[i] = hcode<<8 | bitsused;
        i++;
        rv++;
    }
    ret = LJ92_ERROR_NONE;
#endif
    return ret;
}

static int parseSof3(ljp* self) {
    if (self->ix+6 >= self->datalen) return LJ92_ERROR_CORRUPT;
    self->y = BEH(self->data[self->ix+3]);
    self->x = BEH(self->data[self->ix+5]);
    self->bits = self->data[self->ix+2];
    self->components = self->data[self->ix + 7];
    self->ix += BEH(self->data[self->ix]);
    return LJ92_ERROR_NONE;
}

static int parseBlock(ljp* self) {
    self->ix += BEH(self->data[self->ix]);
    if (self->ix >= self->datalen) return LJ92_ERROR_CORRUPT;
    return LJ92_ERROR_NONE;
}

#ifdef SLOW_HUFF
static int nextbit(ljp* self) {
    u32 b = self->b;
    if (self->cnt == 0) {
        u8* data = &self->data[self->ix];
        u32 next = *data++;
        b = next;
        if (next == 0xff) {
            data++;
            self->ix++;
        }
        self->ix++;
        self->cnt = 8;
    }
    int bit = b >> 7;
    self->cnt--;
    self->b = (b << 1)&0xFF;
    return bit;
}

static int decode(ljp* self) {
    int i = 1;
    int code = nextbit(self);
    while (code > self->maxcode[i]) {
        i++;
        code = (code << 1) + nextbit(self);
    }
    int j = self->valptr[i];
    j = j + code - self->mincode[i];
    int value = self->huffval[j];
    return value;
}

static int receive(ljp* self,int ssss) {
    if (ssss == 16) {
        return 1 << 15;
    }
    int i = 0;
    int v = 0;
    while (i != ssss) {
        i++;
        v = (v<<1) + nextbit(self);
    }
    return v;
}

static int extend(ljp* self,int v,int t) {
    int vt = 1<<(t-1);
    if (v < vt) {
        vt = (-1 << t) + 1;
        v = v + vt;
    }
    return v;
}
#endif

inline static int nextdiff(ljp* self) {
#ifdef SLOW_HUFF
    int t = decode(self);
    int diff = receive(self,t);
    diff = extend(self,diff,t);
#else
    u32 b = self->b;
    int cnt = self->cnt;
    int huffbits = self->huffbits;
    int ix = self->ix;
    int next;
    while (cnt < huffbits) {
        next = *(u16*)&self->data[ix];
        int one = next&0xFF;
        int two = next>>8;
        b = (b<<16)|(one<<8)|two;
        cnt += 16;
        ix += 2;
        if (one==0xFF) {
            b >>= 8;
            cnt -= 8;
        } else if (two==0xFF) ix++;
    }
    int index = b >> (cnt - huffbits);
    u16 ssssused = self->hufflut[index];
    int usedbits = ssssused&0xFF;
    int t = ssssused>>8;
    cnt -= usedbits;
    int keepbitsmask = (1 << cnt)-1;
    b &= keepbitsmask;
    int diff;
    if (t == 16) {
        diff = 1 << 15;
    } else {
        while (cnt < t) {
            next = *(u16*)&self->data[ix];
            int one = next&0xFF;
            int two = next>>8;
            b = (b<<16)|(one<<8)|two;
            cnt += 16;
            ix += 2;
            if (one==0xFF) {
                b >>= 8;
                cnt -= 8;
            } else if (two==0xFF) ix++;
        }
        cnt -= t;
        diff = b >> cnt;
        int vt = 1<<(t-1);
        if (diff < vt) {
            vt = (-1 << t) + 1;
            diff += vt;
        }
    }
    keepbitsmask = (1 << cnt)-1;
    self->b = b & keepbitsmask;
    self->cnt = cnt;
    self->ix = ix;
#endif
    return diff;
}

static int parsePred6(ljp* self) {
    int ret = LJ92_ERROR_CORRUPT;
    self->ix = self->scanstart;
    self->ix += BEH(self->data[self->ix]);
    self->cnt = 0;
    self->b = 0;
    int write = self->writelen;
    int c = 0;
    int pixels = self->y * self->x;
    u16* out = self->image;
    u16* temprow;
    u16* thisrow = self->outrow[0];
    u16* lastrow = self->outrow[1];
    int diff;
    int Px;
    int col = 0;
    int left = 0;
    int linear;
    diff = nextdiff(self);
    Px = 1 << (self->bits-1);
    left = Px + diff;
    left = (u16) (left%65536);
    if (self->linearize)
        linear = self->linearize[left];
    else
        linear = left;
    thisrow[col++] = left;
    out[c++] = linear;
    if (self->ix >= self->datalen) return ret;
    --write;
    int rowcount = self->x-1;
    while (rowcount--) {
        diff = nextdiff(self);
        Px = left;
        left = Px + diff;
        left = (u16) (left%65536);
        if (self->linearize)
            linear = self->linearize[left];
        else
            linear = left;
        thisrow[col++] = left;
        out[c++] = linear;
        if (self->ix >= self->datalen) return ret;
        if (--write==0) {
            out += self->skiplen;
            write = self->writelen;
        }
    }
    temprow = lastrow;
    lastrow = thisrow;
    thisrow = temprow;
    while (c<pixels) {
        col = 0;
        diff = nextdiff(self);
        Px = lastrow[col];
        left = Px + diff;
        left = (u16) (left%65536);
        if (self->linearize) {
            if (left>self->linlen) return LJ92_ERROR_CORRUPT;
            linear = self->linearize[left];
        } else
            linear = left;
        thisrow[col++] = left;
        out[c++] = linear;
        if (self->ix >= self->datalen) break;
        rowcount = self->x-1;
        if (--write==0) {
            out += self->skiplen;
            write = self->writelen;
        }
        while (rowcount--) {
            diff = nextdiff(self);
            Px = lastrow[col] + ((left - lastrow[col-1])>>1);
            left = Px + diff;
            left = (u16) (left%65536);
            if (self->linearize) {
                if (left>self->linlen) return LJ92_ERROR_CORRUPT;
                linear = self->linearize[left];
            } else
                linear = left;
            thisrow[col++] = left;
            out[c++] = linear;
            if (--write==0) {
                out += self->skiplen;
                write = self->writelen;
            }
        }
        temprow = lastrow;
        lastrow = thisrow;
        thisrow = temprow;
        if (self->ix >= self->datalen) break;
    }
    if (c >= pixels) ret = LJ92_ERROR_NONE;
    return ret;
}

static int parseScan(ljp* self) {
    int ret = LJ92_ERROR_CORRUPT;
    self->ix = self->scanstart;
    int compcount = self->data[self->ix+2];
    int pred = self->data[self->ix+3+2*compcount];
    if (pred<0 || pred>7) return ret;
    if (pred==6) return parsePred6(self);
    self->ix += BEH(self->data[self->ix]);
    self->cnt = 0;
    self->b = 0;
    u16* out = self->image;
    u16* thisrow = self->outrow[0];
    u16* lastrow = self->outrow[1];
    int diff;
    int Px = 0;
    int left = 0;
    for (int row = 0; row < self->y; row++) {
        for (int col = 0; col < self->x; col++) {
            int colx = col * self->components;
            for (int c = 0; c < self->components; c++) {
                if ((col==0)&&(row==0)) {
                    Px = 1 << (self->bits-1);
                } else if (row==0) {
                    Px = thisrow[(col - 1) * self->components + c];
                } else if (col==0) {
                    Px = lastrow[c];
                } else {
                    int prev_colx = (col - 1) * self->components;
                    switch (pred) {
                        case 0:
                            Px = 0;
                            break;
                        case 1:
                            Px = thisrow[prev_colx + c];
                            break;
                        case 2:
                            Px = lastrow[colx + c];
                            break;
                        case 3:
                            Px = lastrow[prev_colx + c];
                            break;
                        case 4:
                            Px = left + lastrow[colx + c] - lastrow[prev_colx + c];
                            break;
                        case 5:
                            Px = left + ((lastrow[colx + c] - lastrow[prev_colx + c]) >> 1);
                            break;
                        case 6:
                            Px = lastrow[colx + c] + ((left - lastrow[prev_colx + c]) >> 1);
                            break;
                        case 7:
                            Px = (left + lastrow[colx + c]) >> 1;
                            break;
                    }
                }
                diff = nextdiff(self);
                left = Px + diff;
                left = (u16) (left%65536);
                int linear;
                if (self->linearize) {
                    if (left>self->linlen) return LJ92_ERROR_CORRUPT;
                    linear = self->linearize[left];
                } else
                    linear = left;
                thisrow[colx + c] = left;
                out[colx + c] = linear;
            }
        }
        u16* temprow = lastrow;
        lastrow = thisrow;
        thisrow = temprow;
        out += self->x * self->components + self->skiplen;
    }
    ret = LJ92_ERROR_NONE;
    return ret;
}

static int parseImage(ljp* self) {
    int ret = LJ92_ERROR_NONE;
    while (1) {
        int nextMarker = find(self);
        if (nextMarker == 0xc4)
            ret = parseHuff(self);
        else if (nextMarker == 0xc3)
            ret = parseSof3(self);
        else if (nextMarker == 0xfe)
            ret = parseBlock(self);
        else if (nextMarker == 0xd9)
            break;
        else if (nextMarker == 0xda) {
            self->scanstart = self->ix;
            ret = LJ92_ERROR_NONE;
            break;
        } else if (nextMarker == -1) {
            ret = LJ92_ERROR_CORRUPT;
            break;
        } else
            ret = parseBlock(self);
        if (ret != LJ92_ERROR_NONE) break;
    }
    return ret;
}

static int findSoI(ljp* self) {
    int ret = LJ92_ERROR_CORRUPT;
    if (find(self)==0xd8)
        ret = parseImage(self);
    return ret;
}

static void free_memory(ljp* self) {
#ifdef SLOW_HUFF
    free(self->maxcode);
    self->maxcode = NULL;
    free(self->mincode);
    self->mincode = NULL;
    free(self->valptr);
    self->valptr = NULL;
    free(self->huffval);
    self->huffval = NULL;
    free(self->huffsize);
    self->huffsize = NULL;
    free(self->huffcode);
    self->huffcode = NULL;
#else
    free(self->hufflut);
    self->hufflut = NULL;
#endif
    free(self->rowcache);
    self->rowcache = NULL;
}

int lj92_open(lj92* lj,
              uint8_t* data, int datalen,
              int* width,int* height, int* bitdepth, int* components) {
    ljp* self = (ljp*)calloc(sizeof(ljp),1);
    if (self==NULL) return LJ92_ERROR_NO_MEMORY;
    self->data = (u8*)data;
    self->dataend = self->data + datalen;
    self->datalen = datalen;
    int ret = findSoI(self);
    if (ret == LJ92_ERROR_NONE) {
        u16* rowcache = (u16*)calloc(self->x * self->components * 2, sizeof(u16));
        if (rowcache == NULL) ret = LJ92_ERROR_NO_MEMORY;
        else {
            self->rowcache = rowcache;
            self->outrow[0] = rowcache;
            self->outrow[1] = &rowcache[self->x];
        }
    }
    if (ret != LJ92_ERROR_NONE) {
        *lj = NULL;
        free_memory(self);
        free(self);
    } else {
        *width = self->x;
        *height = self->y;
        *bitdepth = self->bits;
        *components = self->components;
        *lj = self;
    }
    return ret;
}

int lj92_decode(lj92 lj,
                uint16_t* target,int writeLength, int skipLength,
                uint16_t* linearize,int linearizeLength) {
    int ret = LJ92_ERROR_NONE;
    ljp* self = lj;
    if (self == NULL) return LJ92_ERROR_BAD_HANDLE;
    self->image = target;
    self->writelen = writeLength;
    self->skiplen = skipLength;
    self->linearize = linearize;
    self->linlen = linearizeLength;
    ret = parseScan(self);
    return ret;
}

void lj92_close(lj92 lj) {
    ljp* self = lj;
    if (self != NULL)
        free_memory(self);
    free(self);
}

typedef struct _lje {
    uint16_t* image;
    int width;
    int height;
    int bitdepth;
    int readLength;
    int skipLength;
    uint16_t* delinearize;
    int delinearizeLength;
    uint8_t* encoded;
    int encodedWritten;
    int encodedLength;
    int bits[18];
    int huffval[18];
    u16 huffenc[18];
    u16 huffbits[18];
    int huffsym[18];
    uint16_t* rowcache;
} lje;

static void initFixedTable(lje* self) {
    int bits[17] = {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    int huffval[17] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    memcpy(self->bits, bits, sizeof(bits));
    memcpy(self->huffval, huffval, sizeof(huffval));
    int huffcode[18];
    int huffsize[18];
    int k = 0;
    for (int i = 1; i <= 16; i++) {
        for (int j = 1; j <= self->bits[i]; j++) {
            huffsize[k++] = i;
        }
    }
    huffsize[k] = 0;
    k = 0;
    int code = 0;
    int si = huffsize[0];
    while (huffsize[k]) {
        while (huffsize[k] == si) {
            huffcode[k++] = code;
            code++;
        }
        code <<= 1;
        si++;
    }
    memset(self->huffenc, 0, sizeof(self->huffenc));
    memset(self->huffbits, 0, sizeof(self->huffbits));
    memset(self->huffsym, 0, sizeof(self->huffsym));
    for (k = 0; k < 17; k++) {
        int sym = self->huffval[k];
        self->huffsym[sym] = k;
        self->huffenc[k] = huffcode[k];
        self->huffbits[k] = huffsize[k];
    }
}

void writeHeader(lje* self) {
    int w = self->encodedWritten;
    uint8_t* e = self->encoded;
    e[w++] = 0xff; e[w++] = 0xd8;
    e[w++] = 0xff; e[w++] = 0xc4;
    int count = 0;
    for (int i=0;i<17;i++) count += self->bits[i];
    e[w++] = 0x0; e[w++] = 17+2+count;
    e[w++] = 0;
    for (int i=1;i<17;i++) e[w++] = self->bits[i];
    for (int i=0;i<count;i++) e[w++] = self->huffval[i];
    e[w++] = 0xff; e[w++] = 0xc3;
    e[w++] = 0x0; e[w++] = 11;
    e[w++] = self->bitdepth;
    e[w++] = self->height>>8; e[w++] = self->height&0xFF;
    e[w++] = self->width>>8; e[w++] = self->width&0xFF;
    e[w++] = 1; e[w++] = 0; e[w++] = 0x11; e[w++] = 0;
    e[w++] = 0xff; e[w++] = 0xda;
    e[w++] = 0x0; e[w++] = 8;
    e[w++] = 1; e[w++] = 0; e[w++] = 0;
    e[w++] = 6;
    e[w++] = 0; e[w++] = 0;
    self->encodedWritten = w;
}

void writePost(lje* self) {
    int w = self->encodedWritten;
    uint8_t* e = self->encoded;
    e[w++] = 0xff; e[w++] = 0xd9;
    self->encodedWritten = w;
}


int writeBody(lje* self) {
    uint16_t* pixel = self->image;
    int scan = self->readLength;
    uint16_t* rows[2] = { self->rowcache, self->rowcache + self->width };

    uint8_t* out = self->encoded;
    int w = self->encodedWritten;
    int max_w = self->encodedLength - 4;
    uint64_t bit_buffer = 0;
    int bits_in_buffer = 0;

#define ENCODE_DIFF(diff_val) do { \
        int16_t short_diff = (int16_t)(diff_val); \
        int ssss = 0; \
        if (short_diff != 0) { \
            int abs_diff = short_diff < 0 ? -short_diff : short_diff; \
            ssss = 32 - __builtin_clz(abs_diff); \
        } \
        int huffcode = self->huffsym[ssss]; \
        bit_buffer = (bit_buffer << self->huffbits[huffcode]) | self->huffenc[huffcode]; \
        bits_in_buffer += self->huffbits[huffcode]; \
        if (ssss > 0) { \
            int v = short_diff; \
            if (v < 0) v += (1 << ssss) - 1; \
            bit_buffer = (bit_buffer << ssss) | (v & ((1 << ssss) - 1)); \
            bits_in_buffer += ssss; \
        } \
        while (bits_in_buffer >= 8) { \
            if (w >= max_w) { return LJ92_ERROR_ENCODER; } \
            uint8_t byte = (bit_buffer >> (bits_in_buffer - 8)) & 0xFF; \
            out[w++] = byte; \
            if (byte == 0xFF) out[w++] = 0x00; \
            bits_in_buffer -= 8; \
        } \
    } while(0)

    for (int col = 0; col < self->width; col++) {
        uint16_t p = *pixel;
        rows[1][col] = p;
        int Px = (col == 0) ? (1 << (self->bitdepth - 1)) : rows[1][col - 1];
        ENCODE_DIFF(p - Px);
        pixel++; scan--;
        if (scan == 0) { pixel += self->skipLength; scan = self->readLength; }
    }
    uint16_t* tmprow = rows[1]; rows[1] = rows[0]; rows[0] = tmprow;

    for (int row = 1; row < self->height; row++) {
        uint16_t p = *pixel;
        rows[1][0] = p;
        ENCODE_DIFF(p - rows[0][0]);
        pixel++; scan--;
        if (scan == 0) { pixel += self->skipLength; scan = self->readLength; }

        for (int col = 1; col < self->width; col++) {
            p = *pixel;
            rows[1][col] = p;
            int Px = rows[0][col] + ((rows[1][col - 1] - rows[0][col - 1]) >> 1);
            ENCODE_DIFF(p - Px);
            pixel++; scan--;
            if (scan == 0) { pixel += self->skipLength; scan = self->readLength; }
        }
        tmprow = rows[1]; rows[1] = rows[0]; rows[0] = tmprow;
    }

    if (bits_in_buffer > 0) {
        if (w >= max_w) { return LJ92_ERROR_ENCODER; }
        uint8_t byte = (bit_buffer << (8 - bits_in_buffer)) & 0xFF;
        out[w++] = byte;
        if (byte == 0xFF) out[w++] = 0x00;
    }

    self->encodedWritten = w;
    return LJ92_ERROR_NONE;
}


int lj92_encode(uint16_t* image, int width, int height, int bitdepth,
                int readLength, int skipLength,
                uint16_t* delinearize,int delinearizeLength,
                uint8_t** encoded, int* encodedLength) {
    int ret = LJ92_ERROR_NONE;
    lje* self = (lje*)calloc(sizeof(lje),1);
    if (self==NULL) return LJ92_ERROR_NO_MEMORY;
    self->image = image;
    self->width = width;
    self->height = height;
    self->bitdepth = bitdepth;
    self->readLength = readLength;
    self->skipLength = skipLength;
    self->delinearize = delinearize;
    self->delinearizeLength = delinearizeLength;
    self->encodedLength = width * height * 2 + 200;
    self->encoded = malloc(self->encodedLength);
    if (self->encoded==NULL) { free(self); return LJ92_ERROR_NO_MEMORY; }
    // Allocate rowcache for legacy use
    self->rowcache = (uint16_t*)malloc(2 * width * sizeof(uint16_t));
    if (self->rowcache == NULL) { free(self->encoded); free(self); return LJ92_ERROR_NO_MEMORY; }
    initFixedTable(self);
    writeHeader(self);
    ret = writeBody(self);
    if (ret != LJ92_ERROR_NONE) {
        free(self->rowcache);
        free(self->encoded);
        free(self);
        return ret;
    }
    writePost(self);
    self->encoded = realloc(self->encoded,self->encodedWritten);
    self->encodedLength = self->encodedWritten;
    *encoded = self->encoded;
    *encodedLength = self->encodedLength;
    free(self->rowcache);
    free(self);
    return ret;
}

int lj92_encode_direct(uint16_t* image, int width, int height, int bitdepth,
                       int readLength, int skipLength,
                       uint8_t* out_buffer, int out_capacity) {
    int ret = LJ92_ERROR_NONE;
    lje* self = (lje*)calloc(sizeof(lje),1);
    if (self == NULL) return LJ92_ERROR_NO_MEMORY;
    self->image = image;
    self->width = width;
    self->height = height;
    self->bitdepth = bitdepth;
    self->readLength = readLength;
    self->skipLength = skipLength;
    self->delinearize = NULL;
    self->delinearizeLength = 0;
    self->encoded = out_buffer;
    self->encodedLength = out_capacity;
    self->encodedWritten = 0;
    // Allocate rowcache for direct use
    self->rowcache = (uint16_t*)malloc(2 * width * sizeof(uint16_t));
    if (self->rowcache == NULL) { free(self); return LJ92_ERROR_NO_MEMORY; }
    initFixedTable(self);
    writeHeader(self);
    ret = writeBody(self);
    if (ret != LJ92_ERROR_NONE) {
        free(self->rowcache);
        free(self);
        return ret;
    }
    writePost(self);
    int written = self->encodedWritten;
    free(self->rowcache);
    free(self);
    return written;
}


lj92_encoder lj92_encoder_init(int width, int height, int bitdepth,
                               int readLength, int skipLength) {
    lje* self = (lje*)calloc(sizeof(lje), 1);
    if (self == NULL) return NULL;
    self->width = width;
    self->height = height;
    self->bitdepth = bitdepth;
    self->readLength = readLength;
    self->skipLength = skipLength;
    self->rowcache = (uint16_t*)malloc(2 * width * sizeof(uint16_t));
    if (self->rowcache == NULL) {
        free(self);
        return NULL;
    }
    initFixedTable(self);
    return self;
}

int lj92_encode_stateful(lj92_encoder enc, uint16_t* image,
                         uint8_t* out_buffer, int out_capacity) {
    lje* self = (lje*)enc;
    if (self == NULL) return LJ92_ERROR_BAD_HANDLE;
    self->image = image;
    self->encoded = out_buffer;
    self->encodedLength = out_capacity;
    self->encodedWritten = 0;
    writeHeader(self);
    int ret = writeBody(self);
    if (ret != LJ92_ERROR_NONE) return ret;
    writePost(self);
    return self->encodedWritten;
}

void lj92_encoder_close(lj92_encoder enc) {
    lje* self = (lje*)enc;
    if (self != NULL) {
        if (self->rowcache != NULL) free(self->rowcache);
        free(self);
    }
}