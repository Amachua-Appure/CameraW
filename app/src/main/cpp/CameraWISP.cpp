#include <jni.h>
#include <vector>
#include <cmath>
#include <thread>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <arm_neon.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
#include <libavutil/cpu.h>
}

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CameraWISP", __VA_ARGS__)

inline float getShadingGain(const std::vector<float>& map, int cc, int rc, int colorChannel, float nx, float ny) {
    if (cc <= 1 || rc <= 1 || map.empty()) return 1.0f;
    float gx = std::max(0.0f, std::min(1.0f, nx)) * (cc - 1); float gy = std::max(0.0f, std::min(1.0f, ny)) * (rc - 1);
    int ci = std::max(0, std::min(cc - 2, (int)gx)); int ri = std::max(0, std::min(rc - 2, (int)gy));
    float wx = gx - ci; float wy = gy - ri;
    auto getVal = [&](int c, int r) { return map[(r * cc + c) * 4 + colorChannel]; };
    return (getVal(ci, ri) * (1 - wx) + getVal(ci + 1, ri) * wx) * (1 - wy) + (getVal(ci, ri + 1) * (1 - wx) + getVal(ci + 1, ri + 1) * wx) * wy;
}

struct EGLSetup { EGLDisplay display; EGLContext context; EGLSurface surface; };
EGLSetup initHeadlessEGL(int width, int height) {
    EGLSetup setup; setup.display = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(setup.display, nullptr, nullptr);
    const EGLint configAttribs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE };
    EGLint numConfigs; EGLConfig config; eglChooseConfig(setup.display, configAttribs, &config, 1, &numConfigs);
    const EGLint pbufferAttribs[] = { EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE };
    setup.surface = eglCreatePbufferSurface(setup.display, config, pbufferAttribs);
    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, 0x30FB, 1, EGL_NONE };
    setup.context = eglCreateContext(setup.display, config, EGL_NO_CONTEXT, contextAttribs);
    eglMakeCurrent(setup.display, setup.surface, setup.surface, setup.context);
    return setup;
}
void destroyEGL(EGLSetup& setup) {
    eglMakeCurrent(setup.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(setup.display, setup.context); eglDestroySurface(setup.display, setup.surface); eglTerminate(setup.display);
}
GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type); glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
    GLint success; glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) { char infoLog[512]; glGetShaderInfoLog(shader, 512, nullptr, infoLog); LOGE("Shader Failed:\n%s", infoLog); }
    return shader;
}

void calculateMotionGridCPU(const std::vector<float>& refLuma, const std::vector<float>& tgtLuma,
                            int lw, int lh, std::vector<float>& outGrid, int f_idx, int gridW, int gridH) {
    int L_TILE = 16;
    int searchRange = 16;
    int layerOffset = f_idx * gridW * gridH * 2;

    for (int ty = 0; ty < gridH; ty++) {
        for (int tx = 0; tx < gridW; tx++) {
            int startX = tx * L_TILE; int startY = ty * L_TILE;
            int endX = std::min(startX + L_TILE, lw); int endY = std::min(startY + L_TILE, lh);

            int bestDx = 0, bestDy = 0;
            float minSAD = 1e9f;

            for (int dy = -searchRange; dy <= searchRange; dy += 4) {
                for (int dx = -searchRange; dx <= searchRange; dx += 4) {
                    float sad = 0;
                    for (int y = startY; y < endY; y += 2) {
                        int sy = std::clamp(y + dy, 0, lh - 1);
                        for (int x = startX; x < endX; x += 2) {
                            int sx = std::clamp(x + dx, 0, lw - 1);
                            sad += std::abs(refLuma[y*lw+x] - tgtLuma[sy*lw+sx]);
                        }
                    }
                    if (sad < minSAD) { minSAD = sad; bestDx = dx; bestDy = dy; }
                }
            }

            int cX = bestDx, cY = bestDy; minSAD = 1e9f;
            for (int dy = cY - 4; dy <= cY + 4; dy += 1) {
                for (int dx = cX - 4; dx <= cX + 4; dx += 1) {
                    float sad = 0;
                    for (int y = startY; y < endY; y++) {
                        int sy = std::clamp(y + dy, 0, lh - 1);
                        for (int x = startX; x < endX; x++) {
                            int sx = std::clamp(x + dx, 0, lw - 1);
                            sad += std::abs(refLuma[y*lw+x] - tgtLuma[sy*lw+sx]);
                        }
                    }
                    if (sad < minSAD) { minSAD = sad; bestDx = dx; bestDy = dy; }
                }
            }

            auto getSad = [&](int dx, int dy) {
                float sad = 0;
                for (int y = startY; y < endY; y++) {
                    int sy = std::clamp(y + dy, 0, lh - 1);
                    for (int x = startX; x < endX; x++) {
                        int sx = std::clamp(x + dx, 0, lw - 1);
                        sad += std::abs(refLuma[y*lw+x] - tgtLuma[sy*lw+sx]);
                    }
                }
                return sad;
            };

            float sad00 = getSad(bestDx, bestDy);
            float sadM10 = getSad(bestDx - 1, bestDy); float sadP10 = getSad(bestDx + 1, bestDy);
            float sad0M1 = getSad(bestDx, bestDy - 1); float sad0P1 = getSad(bestDx, bestDy + 1);

            float denomX = 2.0f * (sadM10 - 2.0f * sad00 + sadP10);
            float subDx = (denomX > 0.001f) ? (sadM10 - sadP10) / denomX : 0.0f;
            float denomY = 2.0f * (sad0M1 - 2.0f * sad00 + sad0P1);
            float subDy = (denomY > 0.001f) ? (sad0M1 - sad0P1) / denomY : 0.0f;

            float finalDx = (bestDx + std::clamp(subDx, -1.0f, 1.0f)) * 2.0f;
            float finalDy = (bestDy + std::clamp(subDy, -1.0f, 1.0f)) * 2.0f;

            int tileOffset = layerOffset + (ty * gridW + tx) * 2;
            outGrid[tileOffset] = finalDx;
            outGrid[tileOffset + 1] = finalDy;
        }
    }
}

const char* computeShaderSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    precision highp sampler2DArray; precision highp image2D;
    layout(local_size_x = 16, local_size_y = 16) in;

    uniform sampler2DArray rawBurst;
    uniform sampler2DArray motionGrid;
    uniform int yOffset;
    layout(r32f, binding = 0) uniform writeonly highp image2D outputRaw;

    uniform int validFrameCount;
    uniform float noiseScale;
    uniform float noiseOffset;

    void main() {
        ivec2 texelPos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 imgSize = textureSize(rawBurst, 0).xy;
        if (texelPos.x >= imgSize.x || texelPos.y >= imgSize.y) return;

        float refC = texelFetch(rawBurst, ivec3(texelPos, 0), 0).r;
        float refL = texelFetch(rawBurst, ivec3(clamp(texelPos.x - 2, 0, imgSize.x - 1), texelPos.y, 0), 0).r;
        float refR = texelFetch(rawBurst, ivec3(clamp(texelPos.x + 2, 0, imgSize.x - 1), texelPos.y, 0), 0).r;
        float refT = texelFetch(rawBurst, ivec3(texelPos.x, clamp(texelPos.y - 2, 0, imgSize.y - 1), 0), 0).r;
        float refB = texelFetch(rawBurst, ivec3(texelPos.x, clamp(texelPos.y + 2, 0, imgSize.y - 1), 0), 0).r;

        float mean = (refC + refL + refR + refT + refB) * 0.2;
        float var = ((refC-mean)*(refC-mean) + (refL-mean)*(refL-mean) + (refR-mean)*(refR-mean) + (refT-mean)*(refT-mean) + (refB-mean)*(refB-mean)) * 0.25;

        float expectedNoise = (noiseScale * refC) + noiseOffset;
        float tuningDenom = max(expectedNoise, var * 2.0) * 4.0;

        float weightSum = 1.0; float pixelSum = refC;
        vec2 uv = vec2(texelPos) / vec2(imgSize);

        for (int i = 1; i < validFrameCount; i++) {
            vec2 shift = texture(motionGrid, vec3(uv, float(i))).rg;
            vec2 targetFloatPos = vec2(texelPos) + shift;

            if (targetFloatPos.x < 0.0 || targetFloatPos.x > float(imgSize.x - 2) || targetFloatPos.y < 0.0 || targetFloatPos.y > float(imgSize.y - 2)) continue;

            vec2 bayerPhaseOffset = vec2(texelPos % 2);
            vec2 baseFloat = floor((targetFloatPos - bayerPhaseOffset) / 2.0) * 2.0 + bayerPhaseOffset;
            ivec2 basePos = ivec2(baseFloat); vec2 fractPos = (targetFloatPos - baseFloat) / 2.0;

            float c00 = texelFetch(rawBurst, ivec3(basePos, i), 0).r;
            float c10 = texelFetch(rawBurst, ivec3(basePos + ivec2(2, 0), i), 0).r;
            float c01 = texelFetch(rawBurst, ivec3(basePos + ivec2(0, 2), i), 0).r;
            float c11 = texelFetch(rawBurst, ivec3(basePos + ivec2(2, 2), i), 0).r;

            float tgtC = mix(mix(c00, c10, fractPos.x), mix(c01, c11, fractPos.x), fractPos.y);

            float diff = tgtC - refC;
            float w = tuningDenom / (tuningDenom + diff * diff); w = w * w;

            pixelSum += tgtC * w; weightSum += w;
        }
        imageStore(outputRaw, texelPos, vec4(pixelSum / weightSum, 0.0, 0.0, 0.0));
    }
)glsl";

const char* vertexShaderSource = R"glsl(#version 310 es
    const vec2 vertices[4] = vec2[4](vec2(-1.0, -1.0), vec2( 1.0, -1.0), vec2(-1.0,  1.0), vec2( 1.0,  1.0));
    out vec2 vTexCoord;
    void main() { gl_Position = vec4(vertices[gl_VertexID], 0.0, 1.0); vTexCoord = vertices[gl_VertexID] * 0.5 + 0.5; }
)glsl";

const char* fragmentShaderSource = R"glsl(#version 310 es
    precision highp float;
    precision highp int;

    uniform sampler2D rawTexture;
    uniform vec2 texelSize;
    uniform mat3 colorMatrix;
    uniform float rGain;
    uniform float bGain;
    uniform float normFactor;
    uniform int isPq;

    const float exposure = 2.5;
    in vec2 vTexCoord;
    out vec4 fragColor;

    float fetch(float dx, float dy) {
        vec2 target = vTexCoord + vec2(dx, dy) * texelSize;

        if (target.x < 0.0 || target.x > 1.0) dx = -dx;
        if (target.y < 0.0 || target.y > 1.0) dy = -dy;

        vec2 safeUv = clamp(vTexCoord + vec2(dx, dy) * texelSize, 0.0, 1.0);
        return texture(rawTexture, safeUv).r;
    }

    float linearToPq(float c) { float m1 = 2610.0 / 16384.0; float m2 = (2523.0 / 4096.0) * 128.0; float c1 = 3424.0 / 4096.0; float c2 = (2413.0 / 4096.0) * 32.0; float c3 = (2392.0 / 4096.0) * 32.0; float l = pow(max(c * 0.1, 1e-7), m1); return pow((c1 + c2 * l) / (1.0 + c3 * l), m2); }
    float linearToHlg(float c) { if (c <= 1.0 / 12.0) return sqrt(3.0 * max(c, 0.0)); return 0.17883277 * log(max(12.0 * c - 0.28466892, 1e-7)) + 0.55991073; }

    void main() {
        int x = int(gl_FragCoord.x) % 2;
        int y = int(gl_FragCoord.y) % 2;

        float C = fetch(0., 0.);
        float L = fetch(-1., 0.); float R_ = fetch(1., 0.);
        float T = fetch(0., -1.); float B_ = fetch(0., 1.);
        float TL = fetch(-1., -1.); float TR = fetch(1., -1.);
        float BL = fetch(-1., 1.); float BR = fetch(1., 1.);

        float R, G, B;

        if (x == 0 && y == 0) {
            R = C;

            float gH = abs(L - R_) + abs(TL - TR) + abs(BL - BR) + 0.5 * abs(C - fetch(-2., 0.)) + 0.5 * abs(C - fetch(2., 0.));
            float gV = abs(T - B_) + abs(TL - BL) + abs(TR - BR) + 0.5 * abs(C - fetch(0., -2.)) + 0.5 * abs(C - fetch(0., 2.));

            float wH = 1.0 / (pow(gH, 4.0) + 1e-7);
            float wV = 1.0 / (pow(gV, 4.0) + 1e-7);
            float sumW = wH + wV;
            wH /= sumW;
            wV /= sumW;

            float GH = 0.5 * (L + R_) + 0.25 * (2.0 * C - fetch(-2., 0.) - fetch(2., 0.));
            float GV = 0.5 * (T + B_) + 0.25 * (2.0 * C - fetch(0., -2.) - fetch(0., 2.));
            G = wH * GH + wV * GV;
            G = clamp(G, min(min(T, B_), min(L, R_)), max(max(T, B_), max(L, R_)));

            B = G + 0.25 * (TL + TR + BL + BR) - 0.25 * (T + B_ + L + R_);
            B = clamp(B, min(min(TL, TR), min(BL, BR)), max(max(TL, TR), max(BL, BR)));

        } else if (x == 1 && y == 1) {
            B = C;

            float gH = abs(L - R_) + abs(TL - TR) + abs(BL - BR) + 0.5 * abs(C - fetch(-2., 0.)) + 0.5 * abs(C - fetch(2., 0.));
            float gV = abs(T - B_) + abs(TL - BL) + abs(TR - BR) + 0.5 * abs(C - fetch(0., -2.)) + 0.5 * abs(C - fetch(0., 2.));

            float wH = 1.0 / (pow(gH, 4.0) + 1e-7);
            float wV = 1.0 / (pow(gV, 4.0) + 1e-7);
            float sumW = wH + wV;
            wH /= sumW;
            wV /= sumW;

            float GH = 0.5 * (L + R_) + 0.25 * (2.0 * C - fetch(-2., 0.) - fetch(2., 0.));
            float GV = 0.5 * (T + B_) + 0.25 * (2.0 * C - fetch(0., -2.) - fetch(0., 2.));
            G = wH * GH + wV * GV;
            G = clamp(G, min(min(T, B_), min(L, R_)), max(max(T, B_), max(L, R_)));

            R = G + 0.25 * (TL + TR + BL + BR) - 0.25 * (T + B_ + L + R_);
            R = clamp(R, min(min(TL, TR), min(BL, BR)), max(max(TL, TR), max(BL, BR)));

        } else if (x == 1 && y == 0) {
            G = C;
            R = 0.5 * (L + R_) + 0.25 * (2.0 * C - fetch(-2., 0.) - fetch(2., 0.));
            R = clamp(R, min(L, R_), max(L, R_));

            B = 0.5 * (T + B_) + 0.25 * (2.0 * C - fetch(0., -2.) - fetch(0., 2.));
            B = clamp(B, min(T, B_), max(T, B_));

        } else {
            G = C;
            R = 0.5 * (T + B_) + 0.25 * (2.0 * C - fetch(0., -2.) - fetch(0., 2.));
            R = clamp(R, min(T, B_), max(T, B_));

            B = 0.5 * (L + R_) + 0.25 * (2.0 * C - fetch(-2., 0.) - fetch(2., 0.));
            B = clamp(B, min(L, R_), max(L, R_));
        }

        vec3 rgb = vec3(R, G, B);
        rgb.r *= rGain * normFactor * exposure;
        rgb.g *= normFactor * exposure;
        rgb.b *= bGain * normFactor * exposure;

        vec3 corrected = max(vec3(0.0), colorMatrix * rgb);

        float luma = dot(corrected, vec3(0.2627, 0.6780, 0.0593));
        float maxChannel = max(corrected.r, max(corrected.g, corrected.b));
        float desatBlend = smoothstep(0.85, 1.15, maxChannel);
        corrected = mix(corrected, vec3(luma), desatBlend);

        if (isPq == 1) {
            fragColor = vec4(linearToPq(corrected.r), linearToPq(corrected.g), linearToPq(corrected.b), 1.0);
        } else {
            fragColor = vec4(linearToHlg(corrected.r), linearToHlg(corrected.g), linearToHlg(corrected.b), 1.0);
        }
    }
)glsl";

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cameraw_CameraWISP_processBurstNative(
        JNIEnv* env, jobject thiz, jobjectArray framesArray,
        jbyteArray outRgbArray, jint width, jint height, jint blackLevel,
        jfloat rGain, jfloat bGain, jfloat maxVal,
        jfloatArray matrixArray, jint bitDepth,
        jfloatArray lscMapArray, jint lscMapW, jint lscMapH,
        jfloat noiseScale, jfloat noiseOffset) {

    int numFrames = env->GetArrayLength(framesArray);
    float matrix[9]; env->GetFloatArrayRegion(matrixArray, 0, 9, matrix);
    std::vector<float> lscMap(env->GetArrayLength(lscMapArray));
    if (!lscMap.empty()) env->GetFloatArrayRegion(lscMapArray, 0, lscMap.size(), lscMap.data());

    std::vector<jshortArray> localRefs(numFrames);
    std::vector<jshort*> frames(numFrames);
    for (int i = 0; i < numFrames; i++) localRefs[i] = (jshortArray)env->GetObjectArrayElement(framesArray, i);
    for (int i = 0; i < numFrames; i++) frames[i] = (jshort*)env->GetPrimitiveArrayCritical(localRefs[i], nullptr);

    int lw = width / 2; int lh = height / 2;
    std::vector<std::vector<float>> lumaPyramid(numFrames, std::vector<float>(lw * lh));
    std::vector<float> gpuRawArray(width * height * numFrames);

    int gridW = (width + 31) / 32; int gridH = (height + 31) / 32;
    std::vector<float> globalMotionMap(gridW * gridH * numFrames * 2, 0.0f);

    int numThreads = std::thread::hardware_concurrency();
    std::vector<std::thread> workers;

    auto prepWorker = [&](int f_idx) {
        int layerOffset = f_idx * (width * height);
        jshort* src = frames[f_idx];
        bool isDng = (bitDepth == 14);

        for (int y = 0; y < height; y++) {
            int cfaY = y % 2;

            for (int x = 0; x < width; x++) {
                int cfaX = x % 2;
                int colorChannel = (cfaY * 2) + cfaX;

                float val = std::max((src[y * width + x] & 0xFFFF) - blackLevel, 0);

                if (!isDng && lscMapW > 0) {
                    float gain = getShadingGain(lscMap, lscMapW, lscMapH, colorChannel, (float)x/width, (float)y/height);
                    val *= gain;
                }
                gpuRawArray[layerOffset + (y * width + x)] = val;
            }
        }

        for(int y = 0; y < lh; y++){
            for(int x = 0; x < lw; x++){
                int base = (y * 2) * width + (x * 2);
                float sum = (src[base]&0xFFFF) + (src[base+1]&0xFFFF) + (src[base+width]&0xFFFF) + (src[base+width+1]&0xFFFF);
                lumaPyramid[f_idx][y * lw + x] = sum * 0.25f;
            }
        }
    };
    for (int f = 0; f < numFrames; f++) workers.emplace_back(prepWorker, f);
    for (auto& w : workers) w.join(); workers.clear();

    std::vector<float> sharpness(numFrames, 0.0f);
    auto sharpnessWorker = [&](int f_idx) {
        float score = 0.0f;
        const auto& luma = lumaPyramid[f_idx];
        for (int y = 1; y < lh - 1; y += 2) {
            for (int x = 1; x < lw - 1; x += 2) {
                float gx = luma[y*lw + x + 1] - luma[y*lw + x - 1];
                float gy = luma[(y+1)*lw + x] - luma[(y-1)*lw + x];
                score += (gx*gx + gy*gy);
            }
        }
        sharpness[f_idx] = score;
    };

    for (int f = 0; f < numFrames; f++) workers.emplace_back(sharpnessWorker, f);
    for (auto& w : workers) w.join(); workers.clear();

    int bestFrameIdx = 0;
    for(int i = 1; i < numFrames; i++) {
        if(sharpness[i] > sharpness[bestFrameIdx]) bestFrameIdx = i;
    }

    if (bestFrameIdx != 0) {
        LOGE("CameraWISP: Lucky Frame! Swapping Frame 0 with Frame %d (Score: %.0f vs %.0f)",
             bestFrameIdx, sharpness[bestFrameIdx], sharpness[0]);

        std::swap(lumaPyramid[0], lumaPyramid[bestFrameIdx]);

        int layerSize = width * height;
        std::swap_ranges(gpuRawArray.begin(),
                         gpuRawArray.begin() + layerSize,
                         gpuRawArray.begin() + (bestFrameIdx * layerSize));
    }

    auto motionWorker = [&](int f_idx) {
        calculateMotionGridCPU(lumaPyramid[0], lumaPyramid[f_idx], lw, lh, globalMotionMap, f_idx, gridW, gridH);
    };
    for (int f = 1; f < numFrames; f++) workers.emplace_back(motionWorker, f);
    for (auto& w : workers) w.join(); workers.clear();

    for (int i = 0; i < numFrames; i++) env->ReleasePrimitiveArrayCritical(localRefs[i], frames[i], JNI_ABORT);

    float maxR_gain = std::abs(matrix[0]) * rGain + std::abs(matrix[1]) + std::abs(matrix[2]) * bGain;
    float maxG_gain = std::abs(matrix[3]) * rGain + std::abs(matrix[4]) + std::abs(matrix[5]) * bGain;
    float maxB_gain = std::abs(matrix[6]) * rGain + std::abs(matrix[7]) + std::abs(matrix[8]) * bGain;
    float normFactor = 1.0f / (maxVal * std::max({maxR_gain, maxG_gain, maxB_gain}));

    EGLSetup egl = initHeadlessEGL(width, height);

    GLuint computeShader = compileShader(GL_COMPUTE_SHADER, computeShaderSource);
    GLuint computeProgram = glCreateProgram(); glAttachShader(computeProgram, computeShader); glLinkProgram(computeProgram);

    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
    GLuint renderProgram = glCreateProgram(); glAttachShader(renderProgram, vertexShader); glAttachShader(renderProgram, fragmentShader); glLinkProgram(renderProgram);

    GLuint rawBurstTex; glGenTextures(1, &rawBurstTex); glBindTexture(GL_TEXTURE_2D_ARRAY, rawBurstTex);
    glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R32F, width, height, numFrames);
    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, width, height, numFrames, GL_RED, GL_FLOAT, gpuRawArray.data());
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    GLuint motionGridTex; glGenTextures(1, &motionGridTex); glBindTexture(GL_TEXTURE_2D_ARRAY, motionGridTex);
    glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RG16F, gridW, gridH, numFrames);
    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, gridW, gridH, numFrames, GL_RG, GL_FLOAT, globalMotionMap.data());
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    GLuint mergedRawTex; glGenTextures(1, &mergedRawTex); glBindTexture(GL_TEXTURE_2D, mergedRawTex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glUseProgram(computeProgram);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D_ARRAY, rawBurstTex); glUniform1i(glGetUniformLocation(computeProgram, "rawBurst"), 0);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D_ARRAY, motionGridTex); glUniform1i(glGetUniformLocation(computeProgram, "motionGrid"), 1);
    glBindImageTexture(0, mergedRawTex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);

    glUniform1i(glGetUniformLocation(computeProgram, "validFrameCount"), numFrames);
    glUniform1f(glGetUniformLocation(computeProgram, "noiseScale"), noiseScale);
    glUniform1f(glGetUniformLocation(computeProgram, "noiseOffset"), noiseOffset);

    GLint locMergeYOffset = glGetUniformLocation(computeProgram, "yOffset");
    int slicePixelH = 256;
    for (int y = 0; y < height; y += slicePixelH) {
        int curH = std::min(slicePixelH, height - y);
        glUniform1i(locMergeYOffset, y);
        glDispatchCompute((width + 15) / 16, (curH + 15) / 16, 1);
        glFlush();
    }
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);

    if (bitDepth == 14) {
        const char* extSrc = R"glsl(#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(r32f, binding = 0) uniform readonly highp image2D mergedRaw;
            layout(std430, binding = 0) buffer OutBuf { uint data[]; };
            uniform float uBlackLevel;
            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(mergedRaw);
                if(pos.x >= size.x || pos.y >= size.y) return;
                float val = imageLoad(mergedRaw, pos).r;
                data[pos.y * size.x + pos.x] = uint(clamp(val + uBlackLevel, 0.0, 65535.0));
            }
        )glsl";

        GLuint extShader = compileShader(GL_COMPUTE_SHADER, extSrc);
        GLuint extProg = glCreateProgram(); glAttachShader(extProg, extShader); glLinkProgram(extProg);

        GLuint ssbo; glGenBuffers(1, &ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, width * height * sizeof(uint32_t), nullptr, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);

        glUseProgram(extProg);
        glUniform1f(glGetUniformLocation(extProg, "uBlackLevel"), (float)blackLevel);
        glBindImageTexture(0, mergedRawTex, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);

        glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        uint32_t* ptr = (uint32_t*)glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, width * height * sizeof(uint32_t), GL_MAP_READ_BIT);

        jbyteArray result = env->NewByteArray(width * height * 2);
        jbyte* outData = env->GetByteArrayElements(result, nullptr);
        uint16_t* out16 = (uint16_t*)outData;

        if (ptr) {
            for (int i = 0; i < width * height; i++) out16[i] = (uint16_t)ptr[i];
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        }

        env->ReleaseByteArrayElements(result, outData, 0);

        glDeleteBuffers(1, &ssbo); glDeleteProgram(extProg); glDeleteShader(extShader);
        glDeleteTextures(1, &rawBurstTex); glDeleteTextures(1, &mergedRawTex); glDeleteTextures(1, &motionGridTex);
        glDeleteShader(computeShader); glDeleteProgram(computeProgram);
        glDeleteShader(vertexShader); glDeleteShader(fragmentShader); glDeleteProgram(renderProgram);
        destroyEGL(egl);

        return result;
    } else {
        GLuint fbo, finalColorTex; glGenFramebuffers(1, &fbo); glGenTextures(1, &finalColorTex); glBindTexture(GL_TEXTURE_2D, finalColorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, finalColorTex, 0);

        glUseProgram(renderProgram);
        GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, mergedRawTex); glUniform1i(glGetUniformLocation(renderProgram, "rawTexture"), 0);
        glUniform2f(glGetUniformLocation(renderProgram, "texelSize"), 1.0f / width, 1.0f / height);
        glUniform1f(glGetUniformLocation(renderProgram, "rGain"), rGain); glUniform1f(glGetUniformLocation(renderProgram, "bGain"), bGain);
        glUniform1f(glGetUniformLocation(renderProgram, "normFactor"), normFactor); glUniform1i(glGetUniformLocation(renderProgram, "isPq"), (bitDepth == 16) ? 1 : 0);
        float tMat[9] = { matrix[0], matrix[3], matrix[6], matrix[1], matrix[4], matrix[7], matrix[2], matrix[5], matrix[8] };
        glUniformMatrix3fv(glGetUniformLocation(renderProgram, "colorMatrix"), 1, GL_FALSE, tMat);

        glViewport(0, 0, width, height); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        std::vector<float> gpuRgbOutput(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, gpuRgbOutput.data());

        glDeleteVertexArrays(1, &vao); glDeleteShader(computeShader); glDeleteShader(vertexShader); glDeleteShader(fragmentShader); glDeleteProgram(computeProgram); glDeleteProgram(renderProgram); glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &rawBurstTex); glDeleteTextures(1, &mergedRawTex); glDeleteTextures(1, &finalColorTex); glDeleteTextures(1, &motionGridTex); destroyEGL(egl);

        if (bitDepth == 16) {
            int bytesPerPixel = 6; int rowStride = width * bytesPerPixel + 1;
            std::vector<uint8_t> outData(height * rowStride, 0);

            auto encodeWorker16 = [&](int startY, int endY) {
                for (int y = startY; y < endY; y++) {
                    int rowOffset = y * rowStride; outData[rowOffset] = 0;
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        uint16_t fR = (uint16_t)(std::max(0.0f, std::min(1.0f, gpuRgbOutput[i])) * 65535.0f);
                        uint16_t fG = (uint16_t)(std::max(0.0f, std::min(1.0f, gpuRgbOutput[i+1])) * 65535.0f);
                        uint16_t fB = (uint16_t)(std::max(0.0f, std::min(1.0f, gpuRgbOutput[i+2])) * 65535.0f);
                        int pOff = rowOffset + 1 + (x * 6);
                        outData[pOff] = fR >> 8; outData[pOff+1] = fR & 0xFF; outData[pOff+2] = fG >> 8; outData[pOff+3] = fG & 0xFF; outData[pOff+4] = fB >> 8; outData[pOff+5] = fB & 0xFF;
                    }
                }
            };

            int chunk = height / numThreads;
            for (int i = 0; i < numThreads; i++) workers.emplace_back(encodeWorker16, i * chunk, (i == numThreads - 1) ? height : (i + 1) * chunk);
            for (auto& w : workers) w.join(); workers.clear();

            if (outRgbArray != nullptr) env->SetByteArrayRegion(outRgbArray, 0, outData.size(), (const jbyte*)outData.data());
            return nullptr;
        } else {
            int w = width, h = height, uv_w = (w + 1) / 2, uv_h = (h + 1) / 2;
            std::vector<uint16_t> yPlane(w * h, 0), uPlane(uv_w * uv_h, 0), vPlane(uv_w * uv_h, 0);

            auto encodeWorker10 = [&](int startY, int endY) {
                for (int y = startY; y < endY; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        float rH = gpuRgbOutput[i], gH = gpuRgbOutput[i+1], bH = gpuRgbOutput[i+2];
                        float finalY = 0.2627f * rH + 0.6780f * gH + 0.0593f * bH;
                        float finalU = (bH - finalY) / 1.8814f; float finalV = (rH - finalY) / 1.4746f;

                        yPlane[y * w + x] = (uint16_t)(std::max(0.0f, std::min(1.0f, finalY)) * 1023.0f);
                        if (y % 2 == 0 && x % 2 == 0) {
                            int uvIdx = (y / 2) * uv_w + (x / 2);
                            uPlane[uvIdx] = (uint16_t)(std::max(0.0f, std::min(1.0f, finalU + 0.5f)) * 1023.0f);
                            vPlane[uvIdx] = (uint16_t)(std::max(0.0f, std::min(1.0f, finalV + 0.5f)) * 1023.0f);
                        }
                    }
                }
            };

            int chunk = height / numThreads;
            for (int i = 0; i < numThreads; i++) workers.emplace_back(encodeWorker10, i * chunk, (i == numThreads - 1) ? h : (i + 1) * chunk);
            for (auto& w : workers) w.join(); workers.clear();

            av_force_cpu_flags(AV_CPU_FLAG_ARMV8);
            AVFrame* frame = nullptr; AVFormatContext* out_ctx = nullptr; AVPacket* pkt = nullptr;
            uint8_t* dyn_buf = nullptr; jbyteArray result = nullptr; AVStream* stream = nullptr;
            int out_size = 0; const char* format_name = nullptr; const AVCodec* codec = avcodec_find_encoder_by_name("libsvtav1");
            if (!codec) codec = avcodec_find_encoder_by_name("libaom-av1"); if (!codec) codec = avcodec_find_encoder(AV_CODEC_ID_AV1); if (!codec) codec = avcodec_find_encoder(AV_CODEC_ID_HEVC);
            if (!codec) { LOGE("Fatal: No AV1/HEVC Encoder"); return nullptr; }

            AVCodecContext* c = avcodec_alloc_context3(codec);
            c->width = w; c->height = h; c->time_base = {1, 30}; c->pix_fmt = AV_PIX_FMT_YUV420P10LE;
            c->color_primaries = AVCOL_PRI_BT2020; c->color_trc = AVCOL_TRC_ARIB_STD_B67; c->colorspace = AVCOL_SPC_BT2020_NCL; c->color_range = AVCOL_RANGE_JPEG; c->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
            av_opt_set(c->priv_data, "crf", "5", 0); av_opt_set(c->priv_data, "cpu-used", "8", 0); av_opt_set(c->priv_data, "usage", "allintra", 0); av_opt_set(c->priv_data, "still-picture", "1", 0);

            if (avcodec_open2(c, codec, NULL) < 0) goto cleanup;

            frame = av_frame_alloc(); frame->format = c->pix_fmt; frame->width  = c->width; frame->height = c->height; frame->pts = 0;
            if (av_frame_get_buffer(frame, 64) < 0) goto cleanup;

            for (int y = 0; y < h; y++) memcpy(frame->data[0] + y * frame->linesize[0], yPlane.data() + y * w, w * 2);
            for (int y = 0; y < uv_h; y++) { memcpy(frame->data[1] + y * frame->linesize[1], uPlane.data() + y * uv_w, uv_w * 2); memcpy(frame->data[2] + y * frame->linesize[2], vPlane.data() + y * uv_w, uv_w * 2); }

            format_name = (codec->id == AV_CODEC_ID_HEVC) ? "heic" : "avif";
            if (avformat_alloc_output_context2(&out_ctx, nullptr, format_name, nullptr) < 0 || !out_ctx) goto cleanup;
            if (avio_open_dyn_buf(&out_ctx->pb) < 0) goto cleanup;

            stream = avformat_new_stream(out_ctx, NULL); stream->time_base = c->time_base; avcodec_parameters_from_context(stream->codecpar, c);
            if (avformat_write_header(out_ctx, NULL) < 0) goto cleanup;

            pkt = av_packet_alloc();
            avcodec_send_frame(c, frame); while (avcodec_receive_packet(c, pkt) >= 0) { av_interleaved_write_frame(out_ctx, pkt); av_packet_unref(pkt); }
            avcodec_send_frame(c, NULL); while (avcodec_receive_packet(c, pkt) >= 0) { av_interleaved_write_frame(out_ctx, pkt); av_packet_unref(pkt); }
            av_write_trailer(out_ctx);

            out_size = avio_close_dyn_buf(out_ctx->pb, &dyn_buf); out_ctx->pb = nullptr;
            if (out_size > 0 && dyn_buf != nullptr) { result = env->NewByteArray(out_size); env->SetByteArrayRegion(result, 0, out_size, (const jbyte*)dyn_buf); }

            cleanup:
            if (dyn_buf) av_free(dyn_buf); if (pkt) av_packet_free(&pkt); if (frame) av_frame_free(&frame); if (c) avcodec_free_context(&c);
            if (out_ctx) { if (out_ctx->pb) { uint8_t* temp = nullptr; avio_close_dyn_buf(out_ctx->pb, &temp); av_free(temp); } avformat_free_context(out_ctx); }
            return result;
        }
    }
}

static std::mutex g_accMutex;
static int g_frameCount = 0;
static std::vector<uint8_t> g_refY;
static std::vector<uint8_t> g_refUV;
static std::vector<uint16_t> g_accY;
static std::vector<uint16_t> g_accUV;
static std::vector<uint8_t> g_weightY;
static std::vector<uint8_t> g_weightUV;

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_CameraWISP_initYuvAccumulator(JNIEnv* env, jobject thiz, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_accMutex);

    int lumaSize = width * height;
    int chromaSize = (width / 2) * (height / 2) * 2;

    g_refY.assign(lumaSize, 0);
    g_refUV.assign(chromaSize, 0);
    g_accY.assign(lumaSize, 0);
    g_accUV.assign(chromaSize, 0);
    g_weightY.assign(lumaSize, 0);
    g_weightUV.assign(chromaSize, 0);
    g_frameCount = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cameraw_CameraWISP_addYuvFrame(
        JNIEnv* env, jobject thiz,
        jobject yBuf, jobject uBuf, jobject vBuf,
        jint width, jint height,
        jint yStride, jint uvStride, jint uvPixelStride) {

    std::lock_guard<std::mutex> lock(g_accMutex);

    uint8_t* yPlane = (uint8_t*)env->GetDirectBufferAddress(yBuf);
    uint8_t* uPlane = (uint8_t*)env->GetDirectBufferAddress(uBuf);
    uint8_t* vPlane = (uint8_t*)env->GetDirectBufferAddress(vBuf);

    int lumaSize = width * height;
    int halfW = width / 2;
    int halfH = height / 2;

    if (g_frameCount == 0) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int idx = row * width + col;
                uint8_t val = yPlane[row * yStride + col];
                g_refY[idx] = val;
                g_accY[idx] = val;
                g_weightY[idx] = 1;
            }
        }

        int uvOffset = 0;
        for (int row = 0; row < halfH; row++) {
            for (int col = 0; col < halfW; col++) {
                int uvIdx = row * uvStride + col * uvPixelStride;
                g_refUV[uvOffset] = vPlane[uvIdx];
                g_accUV[uvOffset] = vPlane[uvIdx];
                g_weightUV[uvOffset] = 1;
                uvOffset++;

                g_refUV[uvOffset] = uPlane[uvIdx];
                g_accUV[uvOffset] = uPlane[uvIdx];
                g_weightUV[uvOffset] = 1;
                uvOffset++;
            }
        }
        g_frameCount++;
        return;
    }

    int bestDx = 0, bestDy = 0;
    long minSad = -1;
    int searchRange = 24;
    int cx = width / 2;
    int cy = height / 2;
    int patchRadius = 512;

    for (int dy = -searchRange; dy <= searchRange; dy += 2) {
        for (int dx = -searchRange; dx <= searchRange; dx += 2) {
            uint32_t sad = 0;
            for (int y = -patchRadius; y < patchRadius; y += 4) {
                int refY = cy + y;
                int incY = refY + dy;
                uint8_t* refRow = &g_refY[refY * width];
                uint8_t* incRow = &yPlane[incY * yStride];

                uint32x4_t v_sad_accum = vdupq_n_u32(0);
                for (int x = -patchRadius; x < patchRadius; x += 16) {
                    int refX = cx + x;
                    int incX = refX + dx;
                    uint8x16_t v_ref = vld1q_u8(refRow + refX);
                    uint8x16_t v_inc = vld1q_u8(incRow + incX);
                    uint8x16_t v_diff = vabdq_u8(v_ref, v_inc);
                    uint16x8_t v_diff_16 = vpaddlq_u8(v_diff);
                    uint32x4_t v_diff_32 = vpaddlq_u16(v_diff_16);
                    v_sad_accum = vaddq_u32(v_sad_accum, v_diff_32);
                }
                sad += vgetq_lane_u32(v_sad_accum, 0) +
                       vgetq_lane_u32(v_sad_accum, 1) +
                       vgetq_lane_u32(v_sad_accum, 2) +
                       vgetq_lane_u32(v_sad_accum, 3);
            }
            if (minSad == -1 || sad < minSad) {
                minSad = sad;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }

    const int GHOST_THRESH_Y = 25;
    const int GHOST_THRESH_UV = 15;

    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            int srcX = col + bestDx;
            int srcY = row + bestDy;
            int idx = row * width + col;

            if (srcX >= 0 && srcX < width && srcY >= 0 && srcY < height) {
                uint8_t incVal = yPlane[srcY * yStride + srcX];
                uint8_t refVal = g_refY[idx];

                if (std::abs(incVal - refVal) < GHOST_THRESH_Y) {
                    g_accY[idx] += incVal;
                    g_weightY[idx]++;
                }
            }
        }
    }

    int uv_dx = bestDx / 2;
    int uv_dy = bestDy / 2;
    int uvOffset = 0;

    for (int row = 0; row < halfH; row++) {
        for (int col = 0; col < halfW; col++) {
            int srcX = col + uv_dx;
            int srcY = row + uv_dy;

            if (srcX >= 0 && srcX < halfW && srcY >= 0 && srcY < halfH) {
                int uvIdx = srcY * uvStride + srcX * uvPixelStride;
                uint8_t incV = vPlane[uvIdx];
                uint8_t incU = uPlane[uvIdx];

                uint8_t refV = g_refUV[uvOffset];
                uint8_t refU = g_refUV[uvOffset + 1];

                if (std::abs(incV - refV) < GHOST_THRESH_UV && std::abs(incU - refU) < GHOST_THRESH_UV) {
                    g_accUV[uvOffset] += incV;
                    g_weightUV[uvOffset]++;
                    g_accUV[uvOffset + 1] += incU;
                    g_weightUV[uvOffset + 1]++;
                }
            }
            uvOffset += 2;
        }
    }

    g_frameCount++;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cameraw_CameraWISP_finishYuvAccumulator(JNIEnv* env, jobject thiz, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_accMutex);

    int frameSize = width * height * 3 / 2;
    jbyteArray result = env->NewByteArray(frameSize);
    jbyte *outData = env->GetByteArrayElements(result, nullptr);

    int lumaSize = width * height;
    for (int i = 0; i < lumaSize; i++) {
        int weight = g_weightY[i] > 0 ? g_weightY[i] : 1;
        outData[i] = (jbyte) (g_accY[i] / weight);
    }

    int chromaSize = lumaSize / 2;
    for (int i = 0; i < chromaSize; i++) {
        int weight = g_weightUV[i] > 0 ? g_weightUV[i] : 1;
        outData[lumaSize + i] = (jbyte) (g_accUV[i] / weight);
    }

    env->ReleaseByteArrayElements(result, outData, 0);

    g_refY.clear(); g_refY.shrink_to_fit();
    g_refUV.clear(); g_refUV.shrink_to_fit();
    g_accY.clear(); g_accY.shrink_to_fit();
    g_accUV.clear(); g_accUV.shrink_to_fit();
    g_weightY.clear(); g_weightY.shrink_to_fit();
    g_weightUV.clear(); g_weightUV.shrink_to_fit();

    g_frameCount = 0;
    return result;
}