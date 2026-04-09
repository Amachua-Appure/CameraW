#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <vector>
#include <map>
#include <mutex>
#include <cstring>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <queue>
#include <condition_variable>
#include <thread>
#include <atomic>
#include <sys/resource.h>
#include <sys/time.h>
#include <sched.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavformat/avformat.h>
#include <libavutil/opt.h>
#include <libavutil/dovi_meta.h>
}
#define LOG_TAG "CameraW_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_javaVM = nullptr;

class BitWriter {
public:
    std::vector<uint8_t> data;
    uint8_t current_byte = 0;
    int bits_left = 8;

    void write(uint32_t value, int num_bits) {
        while (num_bits > 0) {
            int bits_to_write = std::min(num_bits, bits_left);
            int shift = num_bits - bits_to_write;
            uint8_t mask = (value >> shift) & ((1 << bits_to_write) - 1);
            current_byte = (current_byte << bits_to_write) | mask;
            bits_left -= bits_to_write;
            num_bits -= bits_to_write;

            if (bits_left == 0) {
                data.push_back(current_byte);
                current_byte = 0;
                bits_left = 8;
            }
        }
    }

    void flush() {
        if (bits_left < 8) {
            current_byte <<= bits_left;
            data.push_back(current_byte);
        }
    }
};

class ScopedTimer {
public:
    ScopedTimer(const char* name) : name_(name), start_(std::chrono::high_resolution_clock::now()) {}
    ~ScopedTimer() {
        auto end = std::chrono::high_resolution_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start_).count();
        LOGI("[TIMER] %s took %lld ms", name_, ms);
    }
private:
    const char* name_;
    std::chrono::time_point<std::chrono::high_resolution_clock> start_;
};

static PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;

static void pinThreadToPerformanceCores() {
    int numCores = sysconf(_SC_NPROCESSORS_CONF);
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int startCore = (numCores > 4) ? (numCores / 2) : 0;
    for (int i = startCore; i < numCores; ++i) {
        CPU_SET(i, &cpuset);
    }
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

const char* VERTEX_SHADER = R"(#version 310 es
    layout(location = 0) in vec2 aPos;
    out vec2 vTexCoord;
    void main() {
        vTexCoord = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
        gl_Position = vec4(aPos.x, aPos.y, 0.0, 1.0);
    }
)";

std::string buildFragmentShader() {
    std::string src = R"(#version 310 es
precision highp float;
precision highp int;

uniform highp usampler2D uRawTex;
uniform highp sampler2D uLscTex;

uniform ivec2 uOutSize;
uniform ivec2 uOffset;
uniform vec2 uSensorSize;
uniform vec2 uIntendedSize;
uniform mat3 uCombinedMatrix;
uniform float uBlackLevel;
uniform float uInvRange;
uniform int uLscEnabled;
uniform int uCfa;
uniform int uHdrMode;

in vec2 vTexCoord;
out vec4 outColor;

float getRawValue(ivec2 pos) {
    uint raw = texelFetch(uRawTex, pos >> ivec2(1, 0), 0).r;
    return float((pos.x & 1) == 0 ? (raw & 0xFFFFu) : (raw >> 16u));
}
)";
    src += R"(
void main() {
    ivec2 pos = ivec2(vTexCoord.x * uIntendedSize.x, vTexCoord.y * uIntendedSize.y) + uOffset;
    pos = clamp(pos, ivec2(2), ivec2(int(uSensorSize.x) - 3, int(uSensorSize.y) - 3));

    int row = pos.y % 2;
    int col = pos.x % 2;

    if (uCfa == 1) { col = 1 - col; }
    else if (uCfa == 2) { row = 1 - row; }
    else if (uCfa == 3) { row = 1 - row; col = 1 - col; }

    float C = getRawValue(pos);
    float L = getRawValue(pos + ivec2(-1, 0));
    float R_ = getRawValue(pos + ivec2(1, 0));
    float T = getRawValue(pos + ivec2(0, -1));
    float B_ = getRawValue(pos + ivec2(0, 1));
    float TL = getRawValue(pos + ivec2(-1, -1));
    float TR = getRawValue(pos + ivec2(1, -1));
    float BL = getRawValue(pos + ivec2(-1, 1));
    float BR = getRawValue(pos + ivec2(1, 1));

    float R, G, B;

    if (row == 0 && col == 0) {
        R = C;
        float gH_G = abs(L - R_) + abs(TL - TR) + abs(BL - BR);
        float gV_G = abs(T - B_) + abs(TL - BL) + abs(TR - BR);
        float L2 = getRawValue(pos + ivec2(-2, 0));
        float R2 = getRawValue(pos + ivec2(2, 0));
        float iH_G = 0.5 * (L + R_) + 0.5 * (C - 0.5 * (L2 + R2));
        float T2 = getRawValue(pos + ivec2(0, -2));
        float B2 = getRawValue(pos + ivec2(0, 2));
        float iV_G = 0.5 * (T + B_) + 0.5 * (C - 0.5 * (T2 + B2));
        G = (gH_G < gV_G) ? iH_G : iV_G;
        float gMin = min(min(T, B_), min(L, R_));
        float gMax = max(max(T, B_), max(L, R_));
        G = clamp(G, gMin, gMax);

        B = G + 0.25 * (TL + TR + BL + BR) - 0.25 * (T + B_ + L + R_);
        float bMin = min(min(TL, TR), min(BL, BR));
        float bMax = max(max(TL, TR), max(BL, BR));
        B = clamp(B, bMin, bMax);
    } else if (row == 1 && col == 1) {
        B = C;
        float gH_G = abs(L - R_) + abs(TL - TR) + abs(BL - BR);
        float gV_G = abs(T - B_) + abs(TL - BL) + abs(TR - BR);
        float L2 = getRawValue(pos + ivec2(-2, 0));
        float R2 = getRawValue(pos + ivec2(2, 0));
        float iH_G = 0.5 * (L + R_) + 0.5 * (C - 0.5 * (L2 + R2));
        float T2 = getRawValue(pos + ivec2(0, -2));
        float B2 = getRawValue(pos + ivec2(0, 2));
        float iV_G = 0.5 * (T + B_) + 0.5 * (C - 0.5 * (T2 + B2));
        G = (gH_G < gV_G) ? iH_G : iV_G;
        float gMin = min(min(T, B_), min(L, R_));
        float gMax = max(max(T, B_), max(L, R_));
        G = clamp(G, gMin, gMax);

        R = G + 0.25 * (TL + TR + BL + BR) - 0.25 * (T + B_ + L + R_);
        float rMin = min(min(TL, TR), min(BL, BR));
        float rMax = max(max(TL, TR), max(BL, BR));
        R = clamp(R, rMin, rMax);
    } else if (row == 0 && col == 1) {
        G = C;
        float L2 = getRawValue(pos + ivec2(-2, 0));
        float R2 = getRawValue(pos + ivec2(2, 0));
        R = 0.5 * (L + R_) + 0.5 * (C - 0.5 * (L2 + R2));
        R = clamp(R, min(L, R_), max(L, R_));

        float T2 = getRawValue(pos + ivec2(0, -2));
        float B2 = getRawValue(pos + ivec2(0, 2));
        B = 0.5 * (T + B_) + 0.5 * (C - 0.5 * (T2 + B2));
        B = clamp(B, min(T, B_), max(T, B_));
    } else {
        G = C;
        float T2 = getRawValue(pos + ivec2(0, -2));
        float B2 = getRawValue(pos + ivec2(0, 2));
        R = 0.5 * (T + B_) + 0.5 * (C - 0.5 * (T2 + B2));
        R = clamp(R, min(T, B_), max(T, B_));

        float L2 = getRawValue(pos + ivec2(-2, 0));
        float R2 = getRawValue(pos + ivec2(2, 0));
        B = 0.5 * (L + R_) + 0.5 * (C - 0.5 * (L2 + R2));
        B = clamp(B, min(L, R_), max(L, R_));
    }

    vec3 normalized = max(vec3(0.0), (vec3(R, G, B) - uBlackLevel) * uInvRange);

    if (uLscEnabled == 1) {
        vec2 lscUV = vec2(pos) / uSensorSize;
        vec4 lscGains = texture(uLscTex, lscUV);
        float gGain = (lscGains.g + lscGains.b) * 0.5;
        normalized.r *= lscGains.r;
        normalized.g *= gGain;
        normalized.b *= lscGains.a;
    }

    vec3 corrected = uCombinedMatrix * normalized;

    float luma = dot(corrected, vec3(0.2627, 0.6780, 0.0593));
    float maxChannel = max(corrected.r, max(corrected.g, corrected.b));
    float desatBlend = smoothstep(0.95, 1.5, maxChannel);
    corrected = mix(corrected, vec3(luma), desatBlend);
)";

    src += R"(
    float targetLuminanceScale = (uHdrMode > 0) ? 0.14 : 1.0;
    vec3 linearColor = corrected * targetLuminanceScale;
    vec3 logColor = log2(linearColor + 0.00001);
    float contrast = 1.25;
    float pivot = 0.05;
    vec3 logPivot = vec3(log2(pivot));
    vec3 contrastedLog = logPivot + (logColor - logPivot) * contrast;
    linearColor = max(vec3(0.0), exp2(contrastedLog) - 0.00001);

    if (uHdrMode > 0) {
        vec3 l = exp2(log2(max(linearColor, 0.0)) * (2610.0 / 16384.0));
        vec3 num = (3424.0 / 4096.0) + (2413.0 / 4096.0 * 32.0) * l;
        vec3 den = 1.0 + (2392.0 / 4096.0 * 32.0) * l;
        vec3 pq = exp2(log2(num / den) * (2523.0 / 4096.0 * 128.0));
        vec3 pqFinal = (pq * 0.856304985) + 0.062561094;
        outColor = vec4(pqFinal, 1.0);
    } else {
        vec3 mapped = linearColor / (linearColor + 0.25);
        bvec3 cutoff = lessThan(mapped, vec3(0.018));
        vec3 higher = 1.099 * pow(mapped, vec3(0.45)) - vec3(0.099);
        vec3 lower = 4.5 * mapped;
        vec3 rec709 = mix(higher, lower, cutoff);
        outColor = vec4(clamp(rec709, 0.0, 1.0), 1.0);
    }
    )";
    src += "}\n";
    return src;
}

template <typename T>
class JobQueue {
private:
    std::queue<T> q;
    std::mutex m;
    std::condition_variable cv;
    bool active = true;
public:
    bool push(const T& val) {
        std::lock_guard<std::mutex> lock(m);
        if (!active) return false;
        q.push(val);
        cv.notify_one();
        return true;
    }
    bool pop(T& val) {
        std::unique_lock<std::mutex> lock(m);
        cv.wait(lock, [this] { return !q.empty() || !active; });
        if (!active && q.empty()) return false;
        val = q.front();
        q.pop();
        return true;
    }
    size_t size() {
        std::lock_guard<std::mutex> lock(m);
        return q.size();
    }
    void shutdown() {
        std::lock_guard<std::mutex> lock(m);
        active = false;
        cv.notify_all();
    }
};

struct RawJob {
    AHardwareBuffer* hb;
    jlong ts;
    float wb[4];
    float ccm[9];
    bool hasCcm;
    float exposureGain;
    int fenceFd;
    std::vector<float> lscMap;
    int lscW, lscH;
};

struct GlesContext {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    GLuint renderProgram = 0;
    GLuint rawTex[3] = {0, 0, 0};
    GLuint lscTex = 0;
    int currentTexIdx = 0;
    GLuint vao = 0, vbo = 0;

    GLuint downscaleFBO = 0;
    GLuint downscaleTex = 0;
    static constexpr int DOWNSCALE_SIZE = 64;

    static const int PBO_COUNT = 3;
    GLuint pbo[PBO_COUNT] = {0};
    int64_t pboTs[PBO_COUNT] = {0};
    int pboIdx = 0;
    int framesQueued = 0;

    jobject kotlinBridgeObj = nullptr;
    jmethodID onMetadataMethod = nullptr;

    GLint uRawTexLoc = -1;
    GLint uLscTexLoc = -1;
    GLint uOutSizeLoc = -1;
    GLint uOffsetLoc = -1;
    GLint uSensorSizeLoc = -1;
    GLint uIntendedSizeLoc = -1;
    GLint uCombinedMatrixLoc = -1;
    GLint uBlackLevelLoc = -1;
    GLint uInvRangeLoc = -1;
    GLint uLscEnabledLoc = -1;
    GLint uCfaLoc = -1;
    GLint uHdrModeLoc = -1;

    int sensorW = 0, sensorH = 0;
    int outW = 0, outH = 0;
    int intendedW = 0, intendedH = 0;
    float blackLevel = 0.0f, whiteLevel = 1023.0f;
    float ccm[9] = {1,0,0, 0,1,0, 0,0,1};
    bool surfaceBound = false;
    std::atomic<bool> isRunning{true};
    std::thread renderThread;
    JobQueue<RawJob> rawBufferQueue;
    int hdrMode = 0;
    int cfa = 0;
};

std::map<jlong, GlesContext*> g_contexts;
std::mutex g_registryMutex;
static jlong g_nextHandle = 1;

GLuint createProgram(const char* vSrc, const char* fSrc) {
    auto compile = [](GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        return s;
    };
    GLuint p = glCreateProgram();
    GLuint vs = compile(GL_VERTEX_SHADER, vSrc);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fSrc);
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

void renderWorkerLoop(GlesContext* ctx) {
    pinThreadToPerformanceCores();
    setpriority(PRIO_PROCESS, 0, -10);
    while (ctx->encoderSurface == EGL_NO_SURFACE) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        if (!ctx->isRunning) return;
    }
    eglMakeCurrent(ctx->display, ctx->encoderSurface, ctx->encoderSurface, ctx->context);
    eglSwapInterval(ctx->display, 0);

    std::string fragmentShaderStr = buildFragmentShader();
    ctx->renderProgram = createProgram(VERTEX_SHADER, fragmentShaderStr.c_str());

    ctx->uRawTexLoc = glGetUniformLocation(ctx->renderProgram, "uRawTex");
    ctx->uLscTexLoc = glGetUniformLocation(ctx->renderProgram, "uLscTex");
    ctx->uOutSizeLoc = glGetUniformLocation(ctx->renderProgram, "uOutSize");
    ctx->uOffsetLoc = glGetUniformLocation(ctx->renderProgram, "uOffset");
    ctx->uSensorSizeLoc = glGetUniformLocation(ctx->renderProgram, "uSensorSize");
    ctx->uIntendedSizeLoc = glGetUniformLocation(ctx->renderProgram, "uIntendedSize");
    ctx->uCombinedMatrixLoc = glGetUniformLocation(ctx->renderProgram, "uCombinedMatrix");
    ctx->uBlackLevelLoc = glGetUniformLocation(ctx->renderProgram, "uBlackLevel");
    ctx->uInvRangeLoc = glGetUniformLocation(ctx->renderProgram, "uInvRange");
    ctx->uLscEnabledLoc = glGetUniformLocation(ctx->renderProgram, "uLscEnabled");
    ctx->uCfaLoc = glGetUniformLocation(ctx->renderProgram, "uCfa");
    ctx->uHdrModeLoc = glGetUniformLocation(ctx->renderProgram, "uHdrMode");

    float quadVertices[] = { -1.0f, -1.0f,  1.0f, -1.0f,  -1.0f, 1.0f,  1.0f, 1.0f };
    glGenVertexArrays(1, &ctx->vao);
    glGenBuffers(1, &ctx->vbo);
    glBindVertexArray(ctx->vao);
    glBindBuffer(GL_ARRAY_BUFFER, ctx->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glEnableVertexAttribArray(0);

    glGenTextures(3, ctx->rawTex);
    for (int i = 0; i < 3; ++i) {
        glBindTexture(GL_TEXTURE_2D, ctx->rawTex[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32UI, ctx->sensorW / 2, ctx->sensorH, 0, GL_RED_INTEGER, GL_UNSIGNED_INT, nullptr);
    }

    glGenTextures(1, &ctx->lscTex);
    glBindTexture(GL_TEXTURE_2D, ctx->lscTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenTextures(1, &ctx->downscaleTex);
    glBindTexture(GL_TEXTURE_2D, ctx->downscaleTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ctx->DOWNSCALE_SIZE, ctx->DOWNSCALE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glGenFramebuffers(1, &ctx->downscaleFBO);
    glBindFramebuffer(GL_FRAMEBUFFER, ctx->downscaleFBO);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ctx->downscaleTex, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    glGenBuffers(GlesContext::PBO_COUNT, ctx->pbo);
    for (int i = 0; i < GlesContext::PBO_COUNT; i++) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, ctx->pbo[i]);
        glBufferData(GL_PIXEL_PACK_BUFFER, ctx->DOWNSCALE_SIZE * ctx->DOWNSCALE_SIZE * 4, nullptr, GL_STREAM_READ);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

    ctx->surfaceBound = true;

    RawJob job;
    while (ctx->isRunning) {
        if (!ctx->rawBufferQueue.pop(job)) break;

        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(job.hb, &desc);
        void* bufferPtr = nullptr;

        if (AHardwareBuffer_lock(job.hb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, job.fenceFd, nullptr, &bufferPtr) == 0 && bufferPtr) {
            ctx->currentTexIdx = (ctx->currentTexIdx + 1) % 3;
            GLuint activeTex = ctx->rawTex[ctx->currentTexIdx];

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, activeTex);

            glPixelStorei(GL_UNPACK_ROW_LENGTH, desc.stride / 2);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            {
                ScopedTimer timer("glTexSubImage2D");
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                                desc.width / 2, desc.height,
                                GL_RED_INTEGER, GL_UNSIGNED_INT, bufferPtr);
            }
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);

            AHardwareBuffer_unlock(job.hb, nullptr);
        } else {
            LOGE("Failed to lock HardwareBuffer");
        }
        AHardwareBuffer_release(job.hb);

        glUseProgram(ctx->renderProgram);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, ctx->rawTex[ctx->currentTexIdx]);
        glUniform1i(ctx->uRawTexLoc, 0);

        if (job.lscW > 0 && job.lscH > 0 && !job.lscMap.empty()) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, ctx->lscTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, job.lscW, job.lscH, 0, GL_RGBA, GL_FLOAT, job.lscMap.data());
            glUniform1i(ctx->uLscTexLoc, 1);
            glUniform1i(ctx->uLscEnabledLoc, 1);
        } else {
            glUniform1i(ctx->uLscEnabledLoc, 0);
        }
        glUniform1i(ctx->uCfaLoc, ctx->cfa);
        glUniform1i(ctx->uHdrModeLoc, ctx->hdrMode);
        glUniform2i(ctx->uOutSizeLoc, ctx->outW, ctx->outH);
        glUniform2f(ctx->uSensorSizeLoc, static_cast<float>(ctx->sensorW), static_cast<float>(ctx->sensorH));
        glUniform2f(ctx->uIntendedSizeLoc, static_cast<float>(ctx->intendedW), static_cast<float>(ctx->intendedH));

        int offsetX = std::max(0, (ctx->sensorW - ctx->intendedW) / 2) & ~1;
        int offsetY = std::max(0, (ctx->sensorH - ctx->intendedH) / 2) & ~1;
        glUniform2i(ctx->uOffsetLoc, offsetX, offsetY);

        float range = ctx->whiteLevel - ctx->blackLevel;
        if (range <= 0.0f) range = 1.0f;
        glUniform1f(ctx->uBlackLevelLoc, ctx->blackLevel);
        glUniform1f(ctx->uInvRangeLoc, 1.0f / range);

        float avgGreen = (job.wb[1] + job.wb[2]) * 0.5f;
        if (avgGreen <= 0.0f) avgGreen = 1.0f;
        float rScale = (job.wb[0] / avgGreen) * job.exposureGain;
        float gScale = 1.0f * job.exposureGain;
        float bScale = (job.wb[3] / avgGreen) * job.exposureGain;

        float baseCcm[9];
        if (job.hasCcm) {
            std::memcpy(baseCcm, job.ccm, sizeof(float) * 9);
        } else {
            std::memcpy(baseCcm, ctx->ccm, sizeof(float) * 9);
        }

        float combinedMatrix[9];
        for (int r = 0; r < 3; r++) {
            combinedMatrix[r*3 + 0] = baseCcm[r*3 + 0] * rScale;
            combinedMatrix[r*3 + 1] = baseCcm[r*3 + 1] * gScale;
            combinedMatrix[r*3 + 2] = baseCcm[r*3 + 2] * bScale;
        }
        glUniformMatrix3fv(ctx->uCombinedMatrixLoc, 1, GL_TRUE, combinedMatrix);

        glBindVertexArray(ctx->vao);
        glViewport(0, 0, ctx->outW, ctx->outH);
        {
            ScopedTimer timer("glDrawArrays + eglSwapBuffers");
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            if (ctx->hdrMode > 0 && ctx->hdrMode != 3) {
                glBindFramebuffer(GL_FRAMEBUFFER, ctx->downscaleFBO);
                glViewport(0, 0, ctx->DOWNSCALE_SIZE, ctx->DOWNSCALE_SIZE);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                glBindBuffer(GL_PIXEL_PACK_BUFFER, ctx->pbo[ctx->pboIdx]);
                glReadPixels(0, 0, ctx->DOWNSCALE_SIZE, ctx->DOWNSCALE_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, 0);

                ctx->pboTs[ctx->pboIdx] = job.ts;
                if (ctx->framesQueued < GlesContext::PBO_COUNT) {
                    ctx->framesQueued++;
                }

                int oldestIdx = (ctx->pboIdx + 1) % GlesContext::PBO_COUNT;
                uint8_t* mappedPixels = nullptr;
                int64_t metadataTs = 0;

                if (ctx->framesQueued == GlesContext::PBO_COUNT) {
                    glBindBuffer(GL_PIXEL_PACK_BUFFER, ctx->pbo[oldestIdx]);
                    mappedPixels = (uint8_t*)glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, ctx->DOWNSCALE_SIZE * ctx->DOWNSCALE_SIZE * 4, GL_MAP_READ_BIT);
                    metadataTs = ctx->pboTs[oldestIdx];
                }

                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, ctx->outW, ctx->outH);

                if (mappedPixels) {
                    auto get12BitPq = [](uint8_t val) -> uint32_t {
                        if (val == 0) return 0;
                        float pq_full = val / 255.0f;
                        return static_cast<uint32_t>(pq_full * 4095.0f + 0.5f);
                    };

                    auto pqToNits = [](uint8_t val) -> float {
                        if (val == 0) return 0.0f;
                        float pq_full = val / 255.0f;
                        const float m1 = 2610.0f / 16384.0f;
                        const float m2 = (2523.0f / 4096.0f) * 128.0f;
                        const float c1 = 3424.0f / 4096.0f;
                        const float c2 = (2413.0f / 4096.0f) * 32.0f;
                        const float c3 = (2392.0f / 4096.0f) * 32.0f;
                        float p = std::pow(pq_full, 1.0f / m2);
                        float num = std::max(p - c1, 0.0f);
                        float den = std::max(c2 - c3 * p, 0.0001f);
                        float linear = std::pow(num / den, 1.0f / m1);
                        return std::min(linear * 10000.0f, 10000.0f);
                    };

                    auto nitsTo12BitPq = [](float nits) -> uint32_t {
                        if (nits <= 0.0f) return 0;
                        const float m1 = 2610.0f / 16384.0f;
                        const float m2 = (2523.0f / 4096.0f) * 128.0f;
                        const float c1 = 3424.0f / 4096.0f;
                        const float c2 = (2413.0f / 4096.0f) * 32.0f;
                        const float c3 = (2392.0f / 4096.0f) * 32.0f;
                        float y = std::max(0.0f, std::min(nits / 10000.0f, 1.0f));
                        float p = std::pow(y, m1);
                        float num = c1 + c2 * p;
                        float den = 1.0f + c3 * p;
                        float pq_full = std::pow(num / den, m2);
                        return static_cast<uint32_t>(pq_full * 4095.0f + 0.5f);
                    };

                    uint8_t maxR = 0, maxG = 0, maxB = 0;
                    uint8_t minPixel = 255, maxPixel = 255;
                    double sum_linear_nits = 0.0;

                    std::vector<uint8_t> maxRgbArray;
                    if (ctx->hdrMode == 1) {
                        maxRgbArray.resize(ctx->DOWNSCALE_SIZE * ctx->DOWNSCALE_SIZE);
                    }

                    for (int i = 0; i < ctx->DOWNSCALE_SIZE * ctx->DOWNSCALE_SIZE; ++i) {
                        uint8_t r = mappedPixels[i * 4 + 0];
                        uint8_t g = mappedPixels[i * 4 + 1];
                        uint8_t b = mappedPixels[i * 4 + 2];

                        uint8_t pixelMax = std::max({r, g, b});
                        uint8_t pixelMin = std::min({r, g, b});

                        if (pixelMax > maxPixel) maxPixel = pixelMax;
                        if (pixelMin < minPixel) minPixel = pixelMin;

                        sum_linear_nits += pqToNits(pixelMax);

                        if (ctx->hdrMode == 1) {
                            if (r > maxR) maxR = r;
                            if (g > maxG) maxG = g;
                            if (b > maxB) maxB = b;
                            maxRgbArray[i] = pixelMax;
                        }
                    }

                    float true_avg_nits = static_cast<float>(sum_linear_nits / (ctx->DOWNSCALE_SIZE * ctx->DOWNSCALE_SIZE));
                    std::vector<uint8_t> hdr10p;
                    uint32_t dvMinPq = 0, dvMaxPq = 0, dvAvgPq = 0;

                    if (ctx->hdrMode == 2) {
                        dvMinPq = get12BitPq(minPixel);
                        dvMaxPq = get12BitPq(maxPixel);
                        dvAvgPq = nitsTo12BitPq(true_avg_nits);
                    } else if (ctx->hdrMode == 1) {
                        uint32_t msR = static_cast<uint32_t>(pqToNits(maxR) * 10.0f);
                        uint32_t msG = static_cast<uint32_t>(pqToNits(maxG) * 10.0f);
                        uint32_t msB = static_cast<uint32_t>(pqToNits(maxB) * 10.0f);
                        uint32_t avgRgb17 = static_cast<uint32_t>(true_avg_nits * 10.0f);

                        std::sort(maxRgbArray.begin(), maxRgbArray.end());
                        BitWriter bw;

                        bw.write(0xB5, 8); bw.write(0x00, 8); bw.write(0x3C, 8);
                        bw.write(0x00, 8); bw.write(0x01, 8); bw.write(0x04, 8); bw.write(0x01, 8);
                        bw.write(1, 2); bw.write(0, 27); bw.write(0, 1);
                        bw.write(msR, 17); bw.write(msG, 17); bw.write(msB, 17); bw.write(avgRgb17, 17);
                        bw.write(9, 4);

                        int percentiles[9] = {1, 5, 10, 25, 50, 75, 90, 95, 99};
                        for (int i = 0; i < 9; i++) {
                            bw.write(percentiles[i], 7);
                            int idx = static_cast<int>(maxRgbArray.size() * (percentiles[i] / 100.0f));
                            if (idx >= maxRgbArray.size()) idx = maxRgbArray.size() - 1;
                            uint32_t pNits = static_cast<uint32_t>(pqToNits(maxRgbArray[idx]) * 10.0f);
                            bw.write(pNits, 17);
                        }
                        bw.write(0, 10); bw.write(0, 1); bw.write(0, 1); bw.write(0, 1);
                        bw.flush();
                        hdr10p = bw.data;
                    }

                    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);

                    if (ctx->kotlinBridgeObj && ctx->onMetadataMethod) {
                        JNIEnv* env;
                        int getEnvStat = g_javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
                        bool needDetach = false;
                        if (getEnvStat == JNI_EDETACHED) {
                            if (g_javaVM->AttachCurrentThread(&env, nullptr) == 0) needDetach = true;
                            else env = nullptr;
                        }
                        if (env) {
                            jbyteArray jBytes = env->NewByteArray(hdr10p.size());
                            env->SetByteArrayRegion(jBytes, 0, hdr10p.size(), reinterpret_cast<const jbyte*>(hdr10p.data()));
                            env->CallVoidMethod(ctx->kotlinBridgeObj, ctx->onMetadataMethod,
                                                jBytes, (jint)dvMinPq, (jint)dvMaxPq, (jint)dvAvgPq, metadataTs);
                            env->DeleteLocalRef(jBytes);
                            if (needDetach) g_javaVM->DetachCurrentThread();
                        }
                    }
                } else {
                    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                }

                ctx->pboIdx = (ctx->pboIdx + 1) % GlesContext::PBO_COUNT;
            }

            if (eglPresentationTimeANDROID) eglPresentationTimeANDROID(ctx->display, ctx->encoderSurface, job.ts);
            eglSwapBuffers(ctx->display, ctx->encoderSurface);
        }
    }

    if (ctx->surfaceBound) {
        if (ctx->rawTex[0]) glDeleteTextures(3, ctx->rawTex);
        if (ctx->lscTex) glDeleteTextures(1, &ctx->lscTex);
        if (ctx->downscaleTex) glDeleteTextures(1, &ctx->downscaleTex);
        if (ctx->downscaleFBO) glDeleteFramebuffers(1, &ctx->downscaleFBO);
        if (ctx->renderProgram) glDeleteProgram(ctx->renderProgram);
        if (ctx->vao) glDeleteVertexArrays(1, &ctx->vao);
        if (ctx->vbo) glDeleteBuffers(1, &ctx->vbo);
        if (ctx->pbo[0]) glDeleteBuffers(GlesContext::PBO_COUNT, ctx->pbo);
    }
    eglMakeCurrent(ctx->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeCreate(JNIEnv* env, jobject thiz,
                                              jint outW, jint outH,
                                              jint intendedW, jint intendedH,
                                              jint sensorW, jint sensorH,
                                              jint black, jint white, jint hdrMode,
                                              jint cfa) {
    GlesContext* ctx = new GlesContext();
    ctx->outW = outW; ctx->outH = outH;
    ctx->intendedW = intendedW; ctx->intendedH = intendedH;
    ctx->sensorW = sensorW; ctx->sensorH = sensorH;
    ctx->blackLevel = static_cast<float>(black);
    ctx->whiteLevel = static_cast<float>(white);
    ctx->isRunning = true;
    ctx->hdrMode = hdrMode;
    ctx->cfa = cfa;

    ctx->kotlinBridgeObj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    ctx->onMetadataMethod = env->GetMethodID(clazz, "onDynamicMetadata", "([BIIIJ)V");
    if (!ctx->onMetadataMethod) {
        LOGE("Failed to find onDynamicMetadata method");
    }

    ctx->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(ctx->display, nullptr, nullptr);

    EGLint numConfigs;
    eglGetConfigs(ctx->display, nullptr, 0, &numConfigs);
    std::vector<EGLConfig> allConfigs(numConfigs);
    eglGetConfigs(ctx->display, allConfigs.data(), numConfigs, &numConfigs);
    ctx->config = nullptr;
    for (int i = 0; i < numConfigs; i++) {
        EGLint r = 0, g = 0, b = 0;
        eglGetConfigAttrib(ctx->display, allConfigs[i], EGL_RED_SIZE, &r);
        eglGetConfigAttrib(ctx->display, allConfigs[i], EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(ctx->display, allConfigs[i], EGL_BLUE_SIZE, &b);
        if (r == 10 && g == 10 && b == 10) {
            ctx->config = allConfigs[i];
            LOGI("Found 10-bit EGL Config manually!");
            break;
        }
    }
    if (!ctx->config) {
        LOGE("Failed to find 10-bit config, falling back to 8-bit.");
        const EGLint fallbackAttribs[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL_NONE
        };
        eglChooseConfig(ctx->display, fallbackAttribs, &ctx->config, 1, &numConfigs);
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    ctx->context = eglCreateContext(ctx->display, ctx->config, EGL_NO_CONTEXT, ctxAttribs);
    if (!eglPresentationTimeANDROID) {
        eglPresentationTimeANDROID = (PFNEGLPRESENTATIONTIMEANDROIDPROC) eglGetProcAddress("eglPresentationTimeANDROID");
    }

    ctx->renderThread = std::thread(renderWorkerLoop, ctx);

    std::lock_guard<std::mutex> lock(g_registryMutex);
    jlong handle = g_nextHandle++;
    g_contexts[handle] = ctx;
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeBindEncoderSurface(JNIEnv* env, jobject thiz, jlong handle, jobject surface) {
    auto ctx = g_contexts[handle];
    if (!ctx || !surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    ctx->encoderSurface = eglCreateWindowSurface(ctx->display, ctx->config, window, nullptr);
    ANativeWindow_release(window);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeSetColorMatrix(JNIEnv* env, jobject thiz, jlong handle, jfloatArray matrix) {
    auto ctx = g_contexts[handle];
    if (!ctx) return;
    jsize len = env->GetArrayLength(matrix);
    if (len >= 9) {
        jfloat* mat = env->GetFloatArrayElements(matrix, nullptr);
        memcpy(ctx->ccm, mat, sizeof(float) * 9);
        env->ReleaseFloatArrayElements(matrix, mat, JNI_ABORT);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeProcessFrameBuffer(JNIEnv* env, jobject thiz,
                                                          jlong handle, jobject hBuffer,
                                                          jlong ts, jfloatArray wb,
                                                          jfloatArray ccm, jlong expNs,
                                                          jint iso, jint fenceFd,
                                                          jfloatArray lscMapArray, jint lscW, jint lscH) {
    auto ctx = g_contexts[handle];
    if (!ctx) return env->NewStringUTF("INVALID_HANDLE");
    if (ctx->rawBufferQueue.size() >= 4) {
        if (fenceFd != -1) close(fenceFd);
        LOGI("Dropped frame, queue full");
        return env->NewStringUTF("DROPPED_FRAME");
    }
    RawJob job;
    job.hb = AHardwareBuffer_fromHardwareBuffer(env, hBuffer);
    AHardwareBuffer_acquire(job.hb);
    job.ts = ts;
    job.fenceFd = fenceFd;

    if (wb) env->GetFloatArrayRegion(wb, 0, 4, job.wb);
    else std::fill_n(job.wb, 4, 1.0f);

    if (ccm) {
        env->GetFloatArrayRegion(ccm, 0, 9, job.ccm);
        job.hasCcm = true;
    } else {
        job.hasCcm = false;
    }

    if (lscMapArray && lscW > 0 && lscH > 0) {
        job.lscW = lscW;
        job.lscH = lscH;
        int len = env->GetArrayLength(lscMapArray);
        job.lscMap.resize(len);
        env->GetFloatArrayRegion(lscMapArray, 0, len, job.lscMap.data());
    } else {
        job.lscW = 0;
        job.lscH = 0;
    }

    job.exposureGain = 1.0f;

    ctx->rawBufferQueue.push(job);
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    std::lock_guard<std::mutex> lock(g_registryMutex);
    auto it = g_contexts.find(handle);
    if (it != g_contexts.end()) {
        GlesContext* ctx = it->second;
        ctx->isRunning = false;
        ctx->rawBufferQueue.shutdown();
        if (ctx->renderThread.joinable()) ctx->renderThread.join();
        if (ctx->display != EGL_NO_DISPLAY) {
            if (ctx->encoderSurface != EGL_NO_SURFACE) eglDestroySurface(ctx->display, ctx->encoderSurface);
            if (ctx->context != EGL_NO_CONTEXT) eglDestroyContext(ctx->display, ctx->context);
            eglTerminate(ctx->display);
        }
        if (ctx->kotlinBridgeObj) {
            env->DeleteGlobalRef(ctx->kotlinBridgeObj);
        }
        delete ctx;
        g_contexts.erase(it);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cameraw_VulkanHdrBridge_nativeRemuxVideo(JNIEnv* env, jclass clazz,
                                                  jstring inputPath, jstring outputPath,
                                                  jint sarNum, jint sarDen, jint hdrMode) {
    const char* in_filename = env->GetStringUTFChars(inputPath, nullptr);
    const char* out_filename = env->GetStringUTFChars(outputPath, nullptr);

    AVFormatContext *ifmt_ctx = nullptr, *ofmt_ctx = nullptr;
    int ret;
    jboolean success = JNI_FALSE;

    AVBSFContext *hevc_bsf_ctx = nullptr;
    int video_stream_index = -1;
    AVPacket *pkt = nullptr;

    if ((ret = avformat_open_input(&ifmt_ctx, in_filename, 0, 0)) < 0) {
        LOGE("Could not open input file '%s'", in_filename);
        goto end;
    }
    if ((ret = avformat_find_stream_info(ifmt_ctx, 0)) < 0) {
        LOGE("Failed to retrieve input stream information");
        goto end;
    }

    avformat_alloc_output_context2(&ofmt_ctx, nullptr, nullptr, out_filename);
    if (!ofmt_ctx) {
        LOGE("Could not create output context");
        goto end;
    }

    for (unsigned int i = 0; i < ifmt_ctx->nb_streams; i++) {
        AVStream *in_stream = ifmt_ctx->streams[i];
        AVStream *out_stream = avformat_new_stream(ofmt_ctx, nullptr);
        if (!out_stream) goto end;

        ret = avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar);
        if (ret < 0) goto end;

        if (out_stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = i;

            if (sarNum != 1 || sarDen != 1) {
                out_stream->sample_aspect_ratio = {sarNum, sarDen};
                out_stream->codecpar->sample_aspect_ratio = {sarNum, sarDen};
            }

            if (hdrMode > 0) {
                out_stream->codecpar->color_trc = AVCOL_TRC_SMPTE2084;
                out_stream->codecpar->color_primaries = AVCOL_PRI_BT2020;
                out_stream->codecpar->color_space = AVCOL_SPC_BT2020_NCL;
                out_stream->codecpar->color_range = AVCOL_RANGE_MPEG;
                out_stream->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
            }
        } else {
            out_stream->codecpar->codec_tag = 0;
        }
    }

    if (hdrMode > 0 && video_stream_index >= 0) {
        const AVBitStreamFilter *filter = av_bsf_get_by_name("hevc_metadata");
        if (filter) {
            ret = av_bsf_alloc(filter, &hevc_bsf_ctx);
            if (ret >= 0) {
                ret = avcodec_parameters_copy(hevc_bsf_ctx->par_in, ofmt_ctx->streams[video_stream_index]->codecpar);
                if (ret >= 0) {
                    av_opt_set(hevc_bsf_ctx->priv_data, "chroma_sample_loc_type", "2", 0);
                    ret = av_bsf_init(hevc_bsf_ctx);
                    if (ret >= 0) {
                        avcodec_parameters_copy(ofmt_ctx->streams[video_stream_index]->codecpar, hevc_bsf_ctx->par_out);
                        LOGI("FFmpeg: Initialized hevc_metadata BSF");
                    } else {
                        LOGE("Failed to initialize hevc_metadata BSF");
                        av_bsf_free(&hevc_bsf_ctx);
                    }
                } else {
                    LOGE("Failed to copy codec parameters to BSF");
                    av_bsf_free(&hevc_bsf_ctx);
                }
            } else {
                LOGE("Failed to allocate BSF context");
            }
        } else {
            LOGE("hevc_metadata bitstream filter not found");
        }
    }

    if (!(ofmt_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ofmt_ctx->pb, out_filename, AVIO_FLAG_WRITE);
        if (ret < 0) goto end;
    }

    ofmt_ctx->strict_std_compliance = -2;
    ret = avformat_write_header(ofmt_ctx, nullptr);
    if (ret < 0) goto end;

    pkt = av_packet_alloc();
    if (!pkt) goto end;

    while (av_read_frame(ifmt_ctx, pkt) >= 0) {
        AVStream *in_stream  = ifmt_ctx->streams[pkt->stream_index];
        AVStream *out_stream = ofmt_ctx->streams[pkt->stream_index];

        av_packet_rescale_ts(pkt, in_stream->time_base, out_stream->time_base);
        pkt->pos = -1;

        if (pkt->stream_index == video_stream_index && hevc_bsf_ctx) {
            if (av_bsf_send_packet(hevc_bsf_ctx, pkt) == 0) {
                while (av_bsf_receive_packet(hevc_bsf_ctx, pkt) == 0) {
                    av_interleaved_write_frame(ofmt_ctx, pkt);
                    av_packet_unref(pkt);
                }
            } else {
                av_packet_unref(pkt);
            }
        } else {
            av_interleaved_write_frame(ofmt_ctx, pkt);
            av_packet_unref(pkt);
        }
    }

    if (hevc_bsf_ctx) {
        av_bsf_send_packet(hevc_bsf_ctx, nullptr);
        AVPacket *flush_pkt = av_packet_alloc();
        if (flush_pkt) {
            while (av_bsf_receive_packet(hevc_bsf_ctx, flush_pkt) == 0) {
                av_interleaved_write_frame(ofmt_ctx, flush_pkt);
                av_packet_unref(flush_pkt);
            }
            av_packet_free(&flush_pkt);
        }
        av_bsf_free(&hevc_bsf_ctx);
    }

    av_packet_free(&pkt);

    av_write_trailer(ofmt_ctx);
    success = JNI_TRUE;

    end:
    if (ifmt_ctx) avformat_close_input(&ifmt_ctx);
    if (ofmt_ctx && !(ofmt_ctx->oformat->flags & AVFMT_NOFILE)) avio_closep(&ofmt_ctx->pb);
    if (ofmt_ctx) avformat_free_context(ofmt_ctx);

    env->ReleaseStringUTFChars(inputPath, in_filename);
    env->ReleaseStringUTFChars(outputPath, out_filename);

    return success;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_javaVM = vm;
    return JNI_VERSION_1_6;
}