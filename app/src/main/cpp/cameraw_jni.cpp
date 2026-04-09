#include <jni.h>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <android/log.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>

#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <map>
#include <vector>
#include <sys/uio.h>
#include <atomic>
#include <arm_neon.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

extern "C" {
#include "MLVWriter.h"
#include "MLVFrameUtils.h"
#include "mlv_structs.h"
#include "liblj92/lj92.h"
}

#define LOG_TAG "cameraw_NDK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define MAX_QUEUE_SIZE 25
#define NUM_WORKER_THREADS 4

struct RawFrameTask {
    uint64_t frame_index;
    AHardwareBuffer* hb;
    int offsetX;
    int offsetY;
    int rowStridePixels;
    jlong timestampUs;
    jint iso;
    jlong shutterNs;
    double noise[6];
    bool hasNoise;
    float* lscMap;
    int lscW, lscH;
};

struct CompressedFrame {
    int pool_index;
    size_t size;
    jlong timestampUs;
    jint iso;
    jlong shutterNs;
    long compress_time_ms;
    double noise[6];
    bool hasNoise;
    float* lscMap;
    int lscW, lscH;
};

struct MlvContext {
    FILE* file;
    MLVWriter_t* writer;
    int width;
    int height;
    int bitdepth;
    size_t max_compressed_size;
    std::atomic<bool> is_recording;
    std::atomic<uint64_t> input_frame_counter;
    std::atomic<uint64_t> output_write_counter;
    std::atomic<uint32_t> audio_frame_counter;
    std::queue<RawFrameTask> task_queue;
    std::mutex queue_mutex;
    std::condition_variable queue_cv;
    std::map<uint64_t, CompressedFrame> write_buffer;
    std::mutex write_mutex;
    std::condition_variable write_cv;
    std::vector<std::thread> workers;
    std::thread writer_thread;
    std::mutex file_mutex;
    int out_pool_size;
    std::vector<uint8_t*> out_pool;
    std::queue<int> free_out_pool;
    std::mutex pool_mutex;
    std::condition_variable out_pool_cv;
};

long getCurrentTimeMs() {
    struct timespec res;
    clock_gettime(CLOCK_MONOTONIC, &res);
    return 1000LL * res.tv_sec + res.tv_nsec / 1000000;
}

void worker_thread_func(MlvContext* ctx) {
    setpriority(PRIO_PROCESS, gettid(), -15);
    int W = ctx->width;
    int H = ctx->height;

    while (true) {
        RawFrameTask task;
        {
            std::unique_lock<std::mutex> lock(ctx->queue_mutex);
            ctx->queue_cv.wait(lock, [ctx] {
                return !ctx->task_queue.empty() || !ctx->is_recording;
            });
            if (!ctx->is_recording && ctx->task_queue.empty()) break;
            task = ctx->task_queue.front();
            ctx->task_queue.pop();
        }

        CompressedFrame out_frame;
        out_frame.timestampUs = task.timestampUs;
        out_frame.iso = task.iso;
        out_frame.shutterNs = task.shutterNs;
        out_frame.hasNoise = task.hasNoise;
        if (out_frame.hasNoise) {
            memcpy(out_frame.noise, task.noise, sizeof(task.noise));
            out_frame.lscW = task.lscW;
            out_frame.lscH = task.lscH;
            out_frame.lscMap = task.lscMap;
            task.lscMap = nullptr;
        } else {
            out_frame.lscMap = nullptr;
        }

        int out_pool_idx = -1;
        {
            std::unique_lock<std::mutex> pool_lock(ctx->pool_mutex);
            ctx->out_pool_cv.wait(pool_lock, [ctx] {
                return !ctx->free_out_pool.empty() || !ctx->is_recording;
            });
            if (!ctx->is_recording && ctx->free_out_pool.empty()) break;
            out_pool_idx = ctx->free_out_pool.front();
            ctx->free_out_pool.pop();
        }

        if (task.hb && out_pool_idx >= 0) {
            long startTime = getCurrentTimeMs();
            void* buffer_ptr = nullptr;
            AHardwareBuffer_lock(task.hb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &buffer_ptr);
            uint16_t* in = (uint16_t*)buffer_ptr;
            uint8_t* out = ctx->out_pool[out_pool_idx];
            int out_idx = 0;

            for (int y = 0; y < H; y++) {
                uint16_t* row_in = in + ((task.offsetY + y) * task.rowStridePixels) + task.offsetX;

                int x = 0;

                for (; x <= W - 64; x += 64) {
                    uint16x8_t e_min = vdupq_n_u16(0xFFFF);
                    uint16x8_t e_max = vdupq_n_u16(0);
                    uint16x8_t o_min = vdupq_n_u16(0xFFFF);
                    uint16x8_t o_max = vdupq_n_u16(0);
                    for (int j = 0; j < 64; j += 16) {
                        uint16x8x2_t p = vld2q_u16(&row_in[x + j]);
                        e_min = vminq_u16(e_min, p.val[0]);
                        e_max = vmaxq_u16(e_max, p.val[0]);
                        o_min = vminq_u16(o_min, p.val[1]);
                        o_max = vmaxq_u16(o_max, p.val[1]);
                    }
                    uint16_t min_even = vminvq_u16(e_min);
                    uint16_t max_even = vmaxvq_u16(e_max);
                    uint16_t min_odd  = vminvq_u16(o_min);
                    uint16_t max_odd  = vmaxvq_u16(o_max);
                    uint16_t max_res_even = max_even - min_even;
                    uint16_t max_res_odd  = max_odd - min_odd;
                    uint16_t max_res = std::max(max_res_even, max_res_odd);
                    int bits = (max_res == 0) ? 0 : 32 - __builtin_clz(max_res);
                    if (bits > 16) bits = 16;

                    out[out_idx++] = bits & 0xFF;
                    out[out_idx++] = (min_even >> 8) & 0xFF;
                    out[out_idx++] = min_even & 0xFF;
                    out[out_idx++] = (min_odd >> 8) & 0xFF;
                    out[out_idx++] = min_odd & 0xFF;

                    if (bits == 0) continue;

                    if (bits == 10) {
                        for (int j = 0; j < 64; j += 4) {
                            uint32_t p0 = row_in[x + j] - min_even;
                            uint32_t p1 = row_in[x + j + 1] - min_odd;
                            uint32_t p2 = row_in[x + j + 2] - min_even;
                            uint32_t p3 = row_in[x + j + 3] - min_odd;
                            out[out_idx++] = (p0 >> 2);
                            out[out_idx++] = ((p0 & 0x3) << 6) | (p1 >> 4);
                            out[out_idx++] = ((p1 & 0xF) << 4) | (p2 >> 6);
                            out[out_idx++] = ((p2 & 0x3F) << 2) | (p3 >> 8);
                            out[out_idx++] = (p3 & 0xFF);
                        }
                    } else if (bits == 8) {
                        for (int j = 0; j < 64; j += 2) {
                            out[out_idx++] = (row_in[x + j] - min_even) & 0xFF;
                            out[out_idx++] = (row_in[x + j + 1] - min_odd) & 0xFF;
                        }
                    } else {
                        uint32_t bit_buf = 0;
                        int bit_cnt = 0;
                        for (int j = 0; j < 64; j++) {
                            uint16_t res = row_in[x + j] - ((j % 2 == 0) ? min_even : min_odd);
                            bit_buf = (bit_buf << bits) | res;
                            bit_cnt += bits;
                            while (bit_cnt >= 8) {
                                out[out_idx++] = (bit_buf >> (bit_cnt - 8)) & 0xFF;
                                bit_cnt -= 8;
                            }
                        }
                    }
                }

                for (; x < W; x++) {
                    uint16_t val = row_in[x];
                    out[out_idx++] = (val >> 8) & 0xFF;
                    out[out_idx++] = val & 0xFF;
                }
            }

            AHardwareBuffer_unlock(task.hb, nullptr);
            AHardwareBuffer_release(task.hb);

            out_frame.pool_index = out_pool_idx;
            out_frame.size = out_idx;
            out_frame.compress_time_ms = getCurrentTimeMs() - startTime;
        } else {
            if (task.hb) AHardwareBuffer_release(task.hb);
            out_frame.pool_index = -1;
            out_frame.size = 0;
            out_frame.compress_time_ms = 0;
        }

        {
            std::lock_guard<std::mutex> lock(ctx->write_mutex);
            ctx->write_buffer[task.frame_index] = out_frame;
        }
        ctx->write_cv.notify_one();
    }
}

void writer_thread_func(MlvContext* ctx) {
    setpriority(PRIO_PROCESS, 0, -19);

    while (true) {
        CompressedFrame frame_to_write;
        bool frame_ready = false;

        {
            std::unique_lock<std::mutex> lock(ctx->write_mutex);
            ctx->write_cv.wait(lock, [ctx] {
                return ctx->write_buffer.count(ctx->output_write_counter) > 0 ||
                       (!ctx->is_recording && ctx->output_write_counter >= ctx->input_frame_counter);
            });

            if (ctx->write_buffer.count(ctx->output_write_counter) > 0) {
                frame_to_write = ctx->write_buffer[ctx->output_write_counter];
                ctx->write_buffer.erase(ctx->output_write_counter);
                frame_ready = true;
            } else if (!ctx->is_recording && ctx->output_write_counter >= ctx->input_frame_counter) {
                break;
            }
        }

        if (frame_ready) {
            if (frame_to_write.size > 0 && frame_to_write.pool_index >= 0) {
                long startWrite = getCurrentTimeMs();

                mlv_expo_hdr_t expo_block;
                memset(&expo_block, 0, sizeof(mlv_expo_hdr_t));
                memcpy(expo_block.blockType, "EXPO", 4);
                expo_block.blockSize = sizeof(mlv_expo_hdr_t);
                expo_block.timestamp = frame_to_write.timestampUs;
                expo_block.isoValue = frame_to_write.iso;
                expo_block.shutterValue = frame_to_write.shutterNs / 1000;

                size_t frame_header_size = MLVWriterGetFrameHeaderSize(ctx->writer);
                uint8_t frame_header_data[frame_header_size];
                MLVWriterGetFrameHeaderData(ctx->writer, ctx->output_write_counter,
                                            frame_to_write.size, frame_header_data);
                memcpy(frame_header_data + 16, &frame_to_write.timestampUs, sizeof(uint64_t));

                {
                    std::lock_guard<std::mutex> file_lock(ctx->file_mutex);
                    int fd = fileno(ctx->file);
                    std::vector<struct iovec> iov;
                    iov.push_back({&expo_block, sizeof(mlv_expo_hdr_t)});

                    mlv_c2md_hdr_t c2md_block;
                    size_t lsc_byte_size = 0;
                    if (frame_to_write.hasNoise && frame_to_write.lscMap != nullptr) {
                        memcpy(c2md_block.blockType, "C2MD", 4);
                        lsc_byte_size = frame_to_write.lscW * frame_to_write.lscH * 4 * sizeof(float);
                        c2md_block.blockSize = sizeof(mlv_c2md_hdr_t) + lsc_byte_size;
                        c2md_block.timestamp = frame_to_write.timestampUs;
                        memcpy(c2md_block.noiseProfile, frame_to_write.noise, sizeof(frame_to_write.noise));
                        c2md_block.lscWidth = frame_to_write.lscW;
                        c2md_block.lscHeight = frame_to_write.lscH;
                        iov.push_back({&c2md_block, sizeof(mlv_c2md_hdr_t)});
                        iov.push_back({frame_to_write.lscMap, lsc_byte_size});
                    }

                    iov.push_back({frame_header_data, frame_header_size});
                    iov.push_back({ctx->out_pool[frame_to_write.pool_index], frame_to_write.size});
                    writev(fd, iov.data(), iov.size());
                }

                long writeTime = getCurrentTimeMs() - startWrite;
                LOGI("Frame %llu: Compress: %ldms | Write: %ldms | Size: %.2f MB",
                     (unsigned long long)ctx->output_write_counter,
                     frame_to_write.compress_time_ms,
                     writeTime,
                     frame_to_write.size / 1024.0f / 1024.0f);

                if (frame_to_write.lscMap) free(frame_to_write.lscMap);
            }

            if (frame_to_write.pool_index >= 0) {
                std::lock_guard<std::mutex> pool_lock(ctx->pool_mutex);
                ctx->free_out_pool.push(frame_to_write.pool_index);
                ctx->out_pool_cv.notify_one();
            }

            ctx->output_write_counter++;
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_cameraw_CameraViewModel_initMlvWriter(JNIEnv *env, jobject thiz,
                                               jstring filePath, jint width, jint height,
                                               jint fpsNum, jint fpsDen,
                                               jint blackLevel, jint whiteLevel,
                                               jstring cameraName, jfloat focalLength, jfloat aperture,
                                               jfloatArray colorMatrix, jfloat rGain, jfloat gGain, jfloat bGain, jint cfa,
                                               jfloatArray c2stFloats, jintArray c2stInts, jstring softwareStr,
                                               jint activeW, jint activeH, jint offsetX, jint offsetY) {
    const char *path = env->GetStringUTFChars(filePath, nullptr);
    FILE* f = fopen(path, "wb");
    env->ReleaseStringUTFChars(filePath, path);
    if (!f) return 0;

    setvbuf(f, nullptr, _IOFBF, 1024 * 1024 * 16);

    MlvContext* ctx = new MlvContext();
    ctx->file = f;
    ctx->writer = (MLVWriter_t*)malloc(sizeof_MLVWriter());
    memset(ctx->writer, 0, sizeof_MLVWriter());

    ctx->width = width;
    ctx->height = height;
    ctx->bitdepth = 10;
    ctx->is_recording = true;
    ctx->input_frame_counter = 0;
    ctx->output_write_counter = 0;
    ctx->audio_frame_counter = 0;

    init_MLVWriter(ctx->writer, width, height, ctx->bitdepth, 0,
                   blackLevel, whiteLevel, fpsNum, fpsDen, cfa);

    ctx->writer->MLVI.block.videoClass = 0x01 | 0x40;
    ctx->writer->MLVI.block.audioClass = 1;

    double c_matrix[9] = {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
    if (colorMatrix != nullptr) {
        jfloat* matElements = env->GetFloatArrayElements(colorMatrix, 0);
        for (int i = 0; i < 9; i++) c_matrix[i] = (double)matElements[i];
        env->ReleaseFloatArrayElements(colorMatrix, matElements, 0);
    }
    time_t t = time(NULL);
    struct tm tm = *localtime(&t);
    memcpy(ctx->writer->RTCI.block.blockType, "RTCI", 4);
    ctx->writer->RTCI.block.blockSize = sizeof(mlv_rtci_hdr_t);
    ctx->writer->RTCI.block.tm_year = tm.tm_year;
    ctx->writer->RTCI.block.tm_mon = tm.tm_mon;
    ctx->writer->RTCI.block.tm_mday = tm.tm_mday;
    ctx->writer->RTCI.block.tm_hour = tm.tm_hour;
    ctx->writer->RTCI.block.tm_min = tm.tm_min;
    ctx->writer->RTCI.block.tm_sec = tm.tm_sec;
    ctx->writer->RTCI.write = 1;
    const char *c_cameraName = env->GetStringUTFChars(cameraName, nullptr);
    MLVWriterSetCameraInfo(ctx->writer, (char*)c_cameraName, 0x80000000, c_matrix);
    env->ReleaseStringUTFChars(cameraName, c_cameraName);

    memcpy(ctx->writer->LENS.block.blockType, "LENS", 4);
    ctx->writer->LENS.block.blockSize = sizeof(mlv_lens_hdr_t);
    ctx->writer->LENS.block.focalLength = (uint16_t)(focalLength * 100.0f);
    ctx->writer->LENS.block.aperture = (uint16_t)(aperture * 100.0f);
    ctx->writer->LENS.write = 1;

    size_t header_size = MLVWriterGetHeaderSize(ctx->writer);
    fseek(ctx->file, header_size, SEEK_SET);

#pragma pack(push, 1)
    struct CustomWbal {
        char blockType[4];
        uint32_t blockSize;
        uint64_t timestamp;
        uint32_t wb_r;
        uint32_t wb_g;
        uint32_t wb_b;
        uint32_t wb_g2;
    };

    struct CustomWavi {
        char blockType[4];
        uint32_t blockSize;
        uint64_t timestamp;
        uint16_t format;
        uint16_t channels;
        uint32_t sampleRate;
        uint32_t bytesPerSec;
        uint16_t blockAlign;
        uint16_t bitsPerSample;
    };
#pragma pack(pop)

    CustomWbal custom_wbal = {{'W','B','A','L'}, 32, 0,
                              (uint32_t)(rGain * 1024.0f),
                              (uint32_t)(gGain * 1024.0f),
                              (uint32_t)(bGain * 1024.0f),
                              (uint32_t)(gGain * 1024.0f)};
    fwrite(&custom_wbal, 1, sizeof(custom_wbal), ctx->file);

    CustomWavi custom_wavi = {{'W','A','V','I'}, 32, 0, 1, 2, 48000, 48000 * 4, 4, 16};
    fwrite(&custom_wavi, 1, sizeof(custom_wavi), ctx->file);

    mlv_c2st_hdr_t c2st;
    memset(&c2st, 0, sizeof(c2st));
    memcpy(c2st.blockType, "C2ST", 4);
    c2st.blockSize = sizeof(c2st);

    jfloat* floats = env->GetFloatArrayElements(c2stFloats, 0);
    jint* ints = env->GetIntArrayElements(c2stInts, 0);

    memcpy(c2st.colorMatrix2, floats, 9 * sizeof(float));
    memcpy(c2st.forwardMatrix1, floats + 9, 9 * sizeof(float));
    memcpy(c2st.forwardMatrix2, floats + 18, 9 * sizeof(float));
    memcpy(c2st.calibration1, floats + 27, 9 * sizeof(float));
    memcpy(c2st.calibration2, floats + 36, 9 * sizeof(float));

    c2st.illuminant1 = ints[0];
    c2st.illuminant2 = ints[1];
    c2st.blackLevel[0] = ints[2];
    c2st.blackLevel[1] = ints[3];
    c2st.blackLevel[2] = ints[4];
    c2st.blackLevel[3] = ints[5];
    c2st.activeArea[0] = ints[6];
    c2st.activeArea[1] = ints[7];
    c2st.activeArea[2] = ints[8];
    c2st.activeArea[3] = ints[9];

    const char* sw = env->GetStringUTFChars(softwareStr, 0);
    strncpy(c2st.software, sw, 63);

    fwrite(&c2st, 1, sizeof(c2st), ctx->file);

#pragma pack(push, 1)
    struct MlvCropHdr {
        char blockType[4];
        uint32_t blockSize;
        uint32_t active_w;
        uint32_t active_h;
        uint32_t offset_x;
        uint32_t offset_y;
    };
#pragma pack(pop)

    MlvCropHdr crop_hdr = {
            {'C','R','O','P'},
            sizeof(MlvCropHdr),
            (uint32_t)activeW,
            (uint32_t)activeH,
            (uint32_t)offsetX,
            (uint32_t)offsetY
    };
    fwrite(&crop_hdr, 1, sizeof(crop_hdr), ctx->file);

    fflush(ctx->file);

    env->ReleaseFloatArrayElements(c2stFloats, floats, 0);
    env->ReleaseIntArrayElements(c2stInts, ints, 0);
    env->ReleaseStringUTFChars(softwareStr, sw);

    size_t max_out_size = (width * height * 3) / 2;
    ctx->max_compressed_size = max_out_size;
    ctx->out_pool_size = (width * height > 12000000) ? 6 : 24;

    for (int i = 0; i < ctx->out_pool_size; i++) {
        ctx->out_pool.push_back((uint8_t*)malloc(max_out_size));
        ctx->free_out_pool.push(i);
    }

    for (int i = 0; i < NUM_WORKER_THREADS; i++) {
        ctx->workers.push_back(std::thread(worker_thread_func, ctx));
    }
    ctx->writer_thread = std::thread(writer_thread_func, ctx);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_CameraViewModel_nativeWriteVideoFrameWithMetadata(JNIEnv *env, jobject thiz, jlong contextPtr,
                                                                   jobject hardwareBuffer, jboolean isLeftShifted,
                                                                   jlong timestampUs, jint iso, jlong shutterNs,
                                                                   jdoubleArray noiseArray,
                                                                   jfloatArray lscArray, jint lscW, jint lscH,
                                                                   jint rowStrideBytes, jint offsetX, jint offsetY) {
    MlvContext* ctx = reinterpret_cast<MlvContext*>(contextPtr);
    if (!ctx) return;

    {
        std::lock_guard<std::mutex> lock(ctx->queue_mutex);
        if (ctx->task_queue.size() >= MAX_QUEUE_SIZE) {
            LOGE("Queue full! Dropping frame.");
            return;
        }
    }

    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!hb) {
        LOGE("Failed to get AHardwareBuffer from jobject");
        return;
    }
    AHardwareBuffer_acquire(hb);

    uint64_t frame_idx = ctx->input_frame_counter++;

    double noise[6] = {0};
    if (noiseArray != nullptr) {
        jdouble* noiseElements = env->GetDoubleArrayElements(noiseArray, nullptr);
        jsize len = env->GetArrayLength(noiseArray);
        memcpy(noise, noiseElements, std::min<size_t>(len, 6) * sizeof(double));
        env->ReleaseDoubleArrayElements(noiseArray, noiseElements, JNI_ABORT);
    }

    float* lscCopy = nullptr;
    if (lscArray != nullptr) {
        jsize lscSize = env->GetArrayLength(lscArray);
        if (lscSize > 0) {
            lscCopy = (float*)malloc(lscSize * sizeof(float));
            env->GetFloatArrayRegion(lscArray, 0, lscSize, lscCopy);
        }
    }

    RawFrameTask task;
    task.frame_index = frame_idx;
    task.hb = hb;
    task.offsetX = offsetX;
    task.offsetY = offsetY;
    task.rowStridePixels = rowStrideBytes / sizeof(uint16_t);
    task.timestampUs = timestampUs;
    task.iso = iso;
    task.shutterNs = shutterNs;
    memcpy(task.noise, noise, sizeof(noise));
    task.hasNoise = true;
    task.lscMap = lscCopy;
    task.lscW = lscW;
    task.lscH = lscH;

    {
        std::lock_guard<std::mutex> lock(ctx->queue_mutex);
        ctx->task_queue.push(task);
    }
    ctx->queue_cv.notify_one();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_CameraViewModel_writeAudioFrame(JNIEnv *env, jobject thiz, jlong contextPtr,
                                                 jobject pcmBuffer, jint size, jlong timestampUs) {
    MlvContext* ctx = reinterpret_cast<MlvContext*>(contextPtr);
    if (!ctx || !ctx->is_recording) return;

    void* audio_data = env->GetDirectBufferAddress(pcmBuffer);

    mlv_audf_hdr_t audf_block;
    memcpy(audf_block.blockType, "AUDF", 4);

    uint32_t align_pad = (4 - (size % 4)) % 4;
    audf_block.blockSize = sizeof(mlv_audf_hdr_t) + size + align_pad;
    audf_block.timestamp = timestampUs;
    audf_block.frameNumber = ctx->audio_frame_counter;
    audf_block.frameSpace = 0;

    uint32_t pad = 0;

    {
        std::lock_guard<std::mutex> file_lock(ctx->file_mutex);
        int fd = fileno(ctx->file);

        struct iovec iov[3];
        iov[0].iov_base = &audf_block;
        iov[0].iov_len = sizeof(mlv_audf_hdr_t);

        iov[1].iov_base = audio_data;
        iov[1].iov_len = size;

        int iov_cnt = 2;
        if (align_pad > 0) {
            iov[2].iov_base = &pad;
            iov[2].iov_len = align_pad;
            iov_cnt = 3;
        }

        writev(fd, iov, iov_cnt);
    }

    ctx->audio_frame_counter++;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_CameraViewModel_closeMlvWriter(JNIEnv *env, jobject thiz, jlong contextPtr) {
    MlvContext* ctx = reinterpret_cast<MlvContext*>(contextPtr);
    if (!ctx) return;

    ctx->is_recording = false;
    ctx->queue_cv.notify_all();
    ctx->write_cv.notify_all();

    for (auto& t : ctx->workers) {
        if (t.joinable()) t.join();
    }
    if (ctx->writer_thread.joinable()) {
        ctx->writer_thread.join();
    }

    ctx->writer->MLVI.block.audioFrameCount = ctx->audio_frame_counter;

    fseek(ctx->file, 0, SEEK_SET);
    size_t header_size = MLVWriterGetHeaderSize(ctx->writer);
    void* header_data = malloc(header_size);
    MLVWriterGetHeaderData(ctx->writer, header_data, ctx->output_write_counter);
    fwrite(header_data, 1, header_size, ctx->file);
    free(header_data);
    fclose(ctx->file);
    free(ctx->writer);

    for (uint8_t* buf : ctx->out_pool) {
        free(buf);
    }

    LOGI("MLV File closed successfully. Total frames written: %llu", (unsigned long long)ctx->output_write_counter);
    delete ctx;
}