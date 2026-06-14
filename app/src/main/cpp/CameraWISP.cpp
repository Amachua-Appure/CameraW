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

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

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

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
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
    if (!success) { char infoLog[512]; glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &success); glGetShaderInfoLog(shader, 512, nullptr, infoLog); LOGE("Shader Failed:\n%s", infoLog); }
    return shader;
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
    uniform float rGain;
    uniform float bGain;
    uniform int cfaPattern;

    float getG(sampler2DArray tex, ivec2 pos, int layer, ivec2 imgSize) {
        float t = texelFetch(tex, ivec3(pos.x, clamp(pos.y - 1, 0, imgSize.y - 1), layer), 0).r;
        float b = texelFetch(tex, ivec3(pos.x, clamp(pos.y + 1, 0, imgSize.y - 1), layer), 0).r;
        float l = texelFetch(tex, ivec3(clamp(pos.x - 1, 0, imgSize.x - 1), pos.y, layer), 0).r;
        float r = texelFetch(tex, ivec3(clamp(pos.x + 1, 0, imgSize.x - 1), pos.y, layer), 0).r;
        return (t + b + l + r) * 0.25;
    }

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

        float expectedNoise = max(0.0000001, (noiseScale * max(0.0, refC)) + noiseOffset);
        float tuningDenom = max(expectedNoise, var * 2.0) * 4.0;

        int bayer = ((texelPos.y % 2) << 1) | (texelPos.x % 2);
        int mappedBayer = bayer ^ cfaPattern;
        bool isChroma = (mappedBayer == 0 || mappedBayer == 3);
        float refG = isChroma ? getG(rawBurst, texelPos, 0, imgSize) : refC;

        float wbGain = 1.0;
        if (isChroma) {
            wbGain = (mappedBayer == 0) ? rGain : bGain;
        }

        float refChromaPerceptual = isChroma ? ((refC * wbGain) - refG) : 0.0;
        float refChromaRaw = isChroma ? (refC - refG) : 0.0;

        float lumaWeightSum = 1.0;
        float lumaPixelSum = refG;
        float chromaWeightSum = 1.0;
        float chromaPixelSum = refChromaRaw;

        vec2 uv = vec2(texelPos) / vec2(imgSize);

        for (int i = 1; i < validFrameCount; i++) {
            vec2 shift = texture(motionGrid, vec3(uv, float(i))).rg;
            float motionMagSq = dot(shift, shift);
            float motionGate = exp(-motionMagSq / 16.0);

            vec2 targetFloatPos = vec2(texelPos) + shift;

            if (targetFloatPos.x < 0.0 || targetFloatPos.x > float(imgSize.x - 2) || targetFloatPos.y < 0.0 || targetFloatPos.y > float(imgSize.y - 2)) continue;

            vec2 bayerPhaseOffset = vec2(texelPos % 2);
            vec2 baseFloat = floor((targetFloatPos - bayerPhaseOffset) / 2.0) * 2.0 + bayerPhaseOffset;
            ivec2 basePos = ivec2(baseFloat);
            vec2 fractPos = (targetFloatPos - baseFloat) / 2.0;

            float c00 = texelFetch(rawBurst, ivec3(basePos, i), 0).r;
            float c10 = texelFetch(rawBurst, ivec3(basePos + ivec2(2, 0), i), 0).r;
            float c01 = texelFetch(rawBurst, ivec3(basePos + ivec2(0, 2), i), 0).r;
            float c11 = texelFetch(rawBurst, ivec3(basePos + ivec2(2, 2), i), 0).r;
            float tgtC = mix(mix(c00, c10, fractPos.x), mix(c01, c11, fractPos.x), fractPos.y);

            float tgtG = tgtC;
            float tgtChromaPerceptual = 0.0;
            float tgtChromaRaw = 0.0;

            if (isChroma) {
                float g00 = getG(rawBurst, basePos, i, imgSize);
                float g10 = getG(rawBurst, basePos + ivec2(2, 0), i, imgSize);
                float g01 = getG(rawBurst, basePos + ivec2(0, 2), i, imgSize);
                float g11 = getG(rawBurst, basePos + ivec2(2, 2), i, imgSize);
                tgtG = mix(mix(g00, g10, fractPos.x), mix(g01, g11, fractPos.x), fractPos.y);

                tgtChromaPerceptual = (tgtC * wbGain) - tgtG;
                tgtChromaRaw = tgtC - tgtG;
            }

            float diff = tgtG - refG;
            float diffSq = diff * diff;

            float wLuma = tuningDenom / (tuningDenom + diffSq);
            wLuma = wLuma * wLuma;

            lumaPixelSum += tgtG * wLuma;
            lumaWeightSum += wLuma;

            if (isChroma) {
                float chromaDiff = tgtChromaPerceptual - refChromaPerceptual;
                float chromaDenom = tuningDenom * 2.0;

                float wChroma = chromaDenom / (chromaDenom + (chromaDiff * chromaDiff));

                wChroma = min(wChroma, (wLuma * wLuma) * motionGate);

                chromaPixelSum += tgtChromaRaw * wChroma;
                chromaWeightSum += wChroma;
            }
        }

        float finalLuma = lumaPixelSum / lumaWeightSum;
        float finalChroma = isChroma ? (chromaPixelSum / chromaWeightSum) : 0.0;
        float finalC = isChroma ? (finalLuma + finalChroma) : finalLuma;

        imageStore(outputRaw, texelPos, vec4(finalC, 0.0, 0.0, 0.0));
    }
)glsl";

const char* hlReconstructSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;

    layout(r32f, binding = 0) uniform readonly highp image2D inRaw;
    layout(r32f, binding = 1) uniform writeonly highp image2D outRaw;

    uniform int cfaPattern;
    uniform float clipLevel;
    uniform float rGain;
    uniform float bGain;

    float getRaw(ivec2 p, ivec2 size) {
        return imageLoad(inRaw, clamp(p, ivec2(0), size - 1)).r;
    }

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = imageSize(inRaw);
        if (pos.x >= size.x || pos.y >= size.y) return;

        float val = getRaw(pos, size);

        int bayer = ((pos.y & 1) << 1) | (pos.x & 1);
        int mappedBayer = bayer ^ cfaPattern;
        bool isGreen = (mappedBayer == 1 || mappedBayer == 2);

        float wbGain = 1.0;
        if (mappedBayer == 0) wbGain = rGain;
        else if (mappedBayer == 3) wbGain = bGain;

        float strictClip = clipLevel * 0.995;
        float outVal = val;

        if (val >= strictClip && !isGreen) {
            float ratioSum = 0.0;
            float weightSum = 0.0;

            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    ivec2 sPos = pos + ivec2(dx * 2, dy * 2);
                    float sVal = getRaw(sPos, size);

                    if (sVal < strictClip) {
                        float avgG = (getRaw(sPos + ivec2(1, 0), size) +
                                      getRaw(sPos + ivec2(-1, 0), size) +
                                      getRaw(sPos + ivec2(0, 1), size) +
                                      getRaw(sPos + ivec2(0, -1), size)) * 0.25;

                        if (avgG < strictClip && avgG > 1e-5) {
                            float distSq = float(dx*dx + dy*dy);
                            float weight = 1.0 / (1.0 + distSq);
                            ratioSum += (sVal / avgG) * weight;
                            weightSum += weight;
                        }
                    }
                }
            }

            float localG = (getRaw(pos + ivec2(1, 0), size) +
                            getRaw(pos + ivec2(-1, 0), size) +
                            getRaw(pos + ivec2(0, 1), size) +
                            getRaw(pos + ivec2(0, -1), size)) * 0.25;

            if (weightSum > 0.0 && localG < strictClip) {
                outVal = localG * (ratioSum / weightSum);
            }
        }

        imageStore(outRaw, pos, vec4(outVal * wbGain, 0.0, 0.0, 0.0));
    }
)glsl";

const char* rcdVhlpfSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;
    uniform sampler2D uRawTex; uniform int yOffset;
    layout(rgba16f, binding = 0) uniform writeonly highp image2D outVHLPF;
    uniform float uWhiteBlackRange;

    float raw(ivec2 pos, int dx, int dy, ivec2 size) {
        return texelFetch(uRawTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r / max(1.0, uWhiteBlackRange);
    }
    float V_hpf(ivec2 pos, int dx, int dy, ivec2 size) {
        float val = (raw(pos, dx, dy-3, size) - raw(pos, dx, dy-1, size) - raw(pos, dx, dy+1, size) + raw(pos, dx, dy+3, size)) - 3.0*(raw(pos, dx, dy-2, size) + raw(pos, dx, dy+2, size)) + 6.0*raw(pos, dx, dy, size);
        return val * val;
    }
    float H_hpf(ivec2 pos, int dx, int dy, ivec2 size) {
        float val = (raw(pos, dx-3, dy, size) - raw(pos, dx-1, dy, size) - raw(pos, dx+1, dy, size) + raw(pos, dx+3, dy, size)) - 3.0*(raw(pos, dx-2, dy, size) + raw(pos, dx+2, dy, size)) + 6.0*raw(pos, dx, dy, size);
        return val * val;
    }

    void main() {
        ivec2 pos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 size = textureSize(uRawTex, 0);
        if(pos.x >= size.x || pos.y >= size.y) return;

        float lpf = raw(pos, 0, 0, size) +
                    0.5 * (raw(pos, 0, -1, size) + raw(pos, 0, 1, size) + raw(pos, -1, 0, size) + raw(pos, 1, 0, size)) +
                    0.25 * (raw(pos, -1, -1, size) + raw(pos, 1, -1, size) + raw(pos, -1, 1, size) + raw(pos, 1, 1, size));

        float V_Stat = max(1e-10, V_hpf(pos, 0, -1, size) + V_hpf(pos, 0, 0, size) + V_hpf(pos, 0, 1, size));
        float H_Stat = max(1e-10, H_hpf(pos, -1, 0, size) + H_hpf(pos, 0, 0, size) + H_hpf(pos, 1, 0, size));

        imageStore(outVHLPF, pos, vec4(V_Stat / (V_Stat + H_Stat), lpf, 0.0, 0.0));
    }
)glsl";

const char* rcdGreenSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;
    uniform sampler2D uRawTex; uniform sampler2D uVHLPFTex; uniform int yOffset;
    layout(r32f, binding = 0) uniform writeonly highp image2D outGreen;
    uniform float uWhiteBlackRange;
    uniform int cfaPattern;

    float raw(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uRawTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r / max(1.0, uWhiteBlackRange); }
    float lpf(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uVHLPFTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).g; }
    float vh_dir(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uVHLPFTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r; }

    void main() {
        ivec2 pos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 size = textureSize(uRawTex, 0);
        if(pos.x >= size.x || pos.y >= size.y) return;

        int bayer = ((pos.y & 1) << 1) | (pos.x & 1);
        int mappedBayer = bayer ^ cfaPattern;
        bool isGreen = (mappedBayer == 1 || mappedBayer == 2);

        if (isGreen) {
            imageStore(outGreen, pos, vec4(raw(pos, 0, 0, size), 0.0, 0.0, 0.0));
            return;
        }

        float cfai = raw(pos, 0, 0, size); float eps = 1e-5;
        float N_Grad = eps + (abs(raw(pos, 0, -1, size) - raw(pos, 0, 1, size)) + abs(cfai - raw(pos, 0, -2, size))) + (abs(raw(pos, 0, -1, size) - raw(pos, 0, -3, size)) + abs(raw(pos, 0, -2, size) - raw(pos, 0, -4, size)));
        float S_Grad = eps + (abs(raw(pos, 0, -1, size) - raw(pos, 0, 1, size)) + abs(cfai - raw(pos, 0, 2, size))) + (abs(raw(pos, 0, 1, size) - raw(pos, 0, 3, size)) + abs(raw(pos, 0, 2, size) - raw(pos, 0, 4, size)));
        float W_Grad = eps + (abs(raw(pos, -1, 0, size) - raw(pos, 1, 0, size)) + abs(cfai - raw(pos, -2, 0, size))) + (abs(raw(pos, -1, 0, size) - raw(pos, -3, 0, size)) + abs(raw(pos, -2, 0, size) - raw(pos, -4, 0, size)));
        float E_Grad = eps + (abs(raw(pos, -1, 0, size) - raw(pos, 1, 0, size)) + abs(cfai - raw(pos, 2, 0, size))) + (abs(raw(pos, 1, 0, size) - raw(pos, 3, 0, size)) + abs(raw(pos, 2, 0, size) - raw(pos, 4, 0, size)));

        float lpfi = lpf(pos, 0, 0, size);
        float N_Est = raw(pos, 0, -1, size) * (lpfi + lpfi) / (eps + lpfi + lpf(pos, 0, -1, size));
        float S_Est = raw(pos, 0, 1, size) * (lpfi + lpfi) / (eps + lpfi + lpf(pos, 0, 1, size));
        float W_Est = raw(pos, -1, 0, size) * (lpfi + lpfi) / (eps + lpfi + lpf(pos, -1, 0, size));
        float E_Est = raw(pos, 1, 0, size) * (lpfi + lpfi) / (eps + lpfi + lpf(pos, 1, 0, size));

        float V_Est = (S_Grad * N_Est + N_Grad * S_Est) / (N_Grad + S_Grad);
        float H_Est = (W_Grad * E_Est + E_Grad * W_Est) / (E_Grad + W_Grad);

        float VH_Central = vh_dir(pos, 0, 0, size);
        float VH_Neighbour = 0.25 * (vh_dir(pos, -1, -1, size) + vh_dir(pos, 1, -1, size) + vh_dir(pos, -1, 1, size) + vh_dir(pos, 1, 1, size));
        float VH_Disc = (abs(0.5 - VH_Central) < abs(0.5 - VH_Neighbour)) ? VH_Neighbour : VH_Central;

        imageStore(outGreen, pos, vec4(mix(V_Est, H_Est, VH_Disc), 0.0, 0.0, 0.0));
    }
)glsl";

const char* rcdPqSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;
    uniform sampler2D uRawTex; uniform int yOffset;
    layout(r32f, binding = 0) uniform writeonly highp image2D outPQDir;
    uniform float uWhiteBlackRange;

    float raw(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uRawTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r / max(1.0, uWhiteBlackRange); }
    float P_CDiff(ivec2 pos, int dx, int dy, ivec2 size) { return pow((raw(pos, dx-3, dy-3, size) - raw(pos, dx-1, dy-1, size) - raw(pos, dx+1, dy+1, size) + raw(pos, dx+3, dy+3, size)) - 3.0*(raw(pos, dx-2, dy-2, size) + raw(pos, dx+2, dy+2, size)) + 6.0*raw(pos, dx, dy, size), 2.0); }
    float Q_CDiff(ivec2 pos, int dx, int dy, ivec2 size) { return pow((raw(pos, dx+3, dy-3, size) - raw(pos, dx+1, dy-1, size) - raw(pos, dx-1, dy+1, size) + raw(pos, dx-3, dy+3, size)) - 3.0*(raw(pos, dx+2, dy-2, size) + raw(pos, dx-2, dy+2, size)) + 6.0*raw(pos, dx, dy, size), 2.0); }

    void main() {
        ivec2 pos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 size = textureSize(uRawTex, 0);
        if(pos.x >= size.x || pos.y >= size.y) return;

        float p_stat = max(1e-10, P_CDiff(pos, -1, -1, size) + P_CDiff(pos, 0, 0, size) + P_CDiff(pos, 1, 1, size));
        float q_stat = max(1e-10, Q_CDiff(pos, 1, -1, size) + Q_CDiff(pos, 0, 0, size) + Q_CDiff(pos, -1, 1, size));
        imageStore(outPQDir, pos, vec4(p_stat / (p_stat + q_stat), 0.0, 0.0, 0.0));
    }
)glsl";

const char* rcdChromaBrSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;
    uniform sampler2D uRawTex; uniform sampler2D uGreenTex; uniform sampler2D uPQTex; uniform int yOffset;
    layout(rgba16f, binding = 0) uniform writeonly highp image2D outChroma;
    uniform float uWhiteBlackRange;
    uniform int cfaPattern;

    float raw(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uRawTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r / max(1.0, uWhiteBlackRange); }
    float green(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uGreenTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r; }
    float pq_dir(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uPQTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r; }

    void main() {
        ivec2 pos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 size = textureSize(uRawTex, 0);
        if(pos.x >= size.x || pos.y >= size.y) return;

        int bayer = ((pos.y & 1) << 1) | (pos.x & 1);
        int mappedBayer = bayer ^ cfaPattern;
        bool isRed = (mappedBayer == 0);
        bool isBlue = (mappedBayer == 3);
        float g_center = green(pos, 0, 0, size);

        if (!isRed && !isBlue) {
            imageStore(outChroma, pos, vec4(0.0, g_center, 0.0, 1.0)); return;
        }

        float PQ_Central = pq_dir(pos, 0, 0, size);
        float PQ_Neighbour = 0.25 * (pq_dir(pos, -1, -1, size) + pq_dir(pos, 1, -1, size) + pq_dir(pos, -1, 1, size) + pq_dir(pos, 1, 1, size));
        float PQ_Disc = (abs(0.5 - PQ_Central) < abs(0.5 - PQ_Neighbour)) ? PQ_Neighbour : PQ_Central;

        float eps = 1e-5;
        float c_NW = raw(pos, -1, -1, size); float c_NE = raw(pos, 1, -1, size);
        float c_SW = raw(pos, -1, 1, size);  float c_SE = raw(pos, 1, 1, size);

        float NW_Grad = eps + abs(c_NW - c_SE) + abs(c_NW - raw(pos, -3, -3, size)) + abs(g_center - green(pos, -2, -2, size));
        float NE_Grad = eps + abs(c_NE - c_SW) + abs(c_NE - raw(pos, 3, -3, size)) + abs(g_center - green(pos, 2, -2, size));
        float SW_Grad = eps + abs(c_SW - c_NE) + abs(c_SW - raw(pos, -3, 3, size)) + abs(g_center - green(pos, -2, 2, size));
        float SE_Grad = eps + abs(c_SE - c_NW) + abs(c_SE - raw(pos, 3, 3, size)) + abs(g_center - green(pos, 2, 2, size));

        float P_Est = (NW_Grad * (c_SE - green(pos, 1, 1, size)) + SE_Grad * (c_NW - green(pos, -1, -1, size))) / (NW_Grad + SE_Grad);
        float Q_Est = (NE_Grad * (c_SW - green(pos, -1, 1, size)) + SW_Grad * (c_NE - green(pos, 1, -1, size))) / (NE_Grad + SW_Grad);

        float interpolated_diff = mix(P_Est, Q_Est, PQ_Disc);

        float diff_NW = c_NW - green(pos, -1, -1, size);
        float diff_NE = c_NE - green(pos,  1, -1, size);
        float diff_SW = c_SW - green(pos, -1,  1, size);
        float diff_SE = c_SE - green(pos,  1,  1, size);

        float min_diff = min(min(diff_NW, diff_NE), min(diff_SW, diff_SE));
        float max_diff = max(max(diff_NW, diff_NE), max(diff_SW, diff_SE));
        interpolated_diff = clamp(interpolated_diff, min_diff, max_diff);

        float interp = g_center + interpolated_diff;

        if (isRed) imageStore(outChroma, pos, vec4(raw(pos, 0, 0, size), g_center, interp, 1.0));
        else imageStore(outChroma, pos, vec4(interp, g_center, raw(pos, 0, 0, size), 1.0));
    }
)glsl";

const char* rcdRgbgSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;
    uniform sampler2D uChromaTex; uniform sampler2D uVHLPFTex; uniform int yOffset;
    layout(rgba16f, binding = 0) uniform writeonly highp image2D outRGB;
    uniform int cfaPattern;

    vec4 chroma(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uChromaTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0); }
    float vh_dir(ivec2 pos, int dx, int dy, ivec2 size) { return texelFetch(uVHLPFTex, clamp(pos + ivec2(dx, dy), ivec2(0), size - 1), 0).r; }

    float solve(float cN, float cS, float cW, float cE, float cNN, float cSS, float cWW, float cEE,
                float N1, float S1, float W1, float E1,
                float rgb1mw1, float rgb1pw1, float rgb1m1, float rgb1p1, float VH_Disc) {
        float SNabs = abs(cN - cS); float EWabs = abs(cW - cE);
        float N_Grad = N1 + SNabs + abs(cN - cNN); float S_Grad = S1 + SNabs + abs(cS - cSS);
        float W_Grad = W1 + EWabs + abs(cW - cWW); float E_Grad = E1 + EWabs + abs(cE - cEE);
        float V_Est = (N_Grad * (cS - rgb1pw1) + S_Grad * (cN - rgb1mw1)) / (N_Grad + S_Grad);
        float H_Est = (E_Grad * (cW - rgb1m1) + W_Grad * (cE - rgb1p1)) / (E_Grad + W_Grad);
        return mix(V_Est, H_Est, VH_Disc);
    }

    void main() {
        ivec2 pos = ivec2(int(gl_GlobalInvocationID.x), int(gl_GlobalInvocationID.y) + yOffset);
        ivec2 size = textureSize(uChromaTex, 0);
        if(pos.x >= size.x || pos.y >= size.y) return;

        int bayer = ((pos.y & 1) << 1) | (pos.x & 1);
        int mappedBayer = bayer ^ cfaPattern;
        bool isGreen = (mappedBayer == 1 || mappedBayer == 2);
        vec4 center = chroma(pos, 0, 0, size);

        if (!isGreen) { imageStore(outRGB, pos, vec4(max(center.rgb, vec3(0.0)), 1.0)); return; }

        float VH_Central = vh_dir(pos, 0, 0, size);
        float VH_Neighbour = 0.25 * (vh_dir(pos, -1, -1, size) + vh_dir(pos, 1, -1, size) + vh_dir(pos, -1, 1, size) + vh_dir(pos, 1, 1, size));
        float VH_Disc = (abs(0.5 - VH_Central) < abs(0.5 - VH_Neighbour)) ? VH_Neighbour : VH_Central;

        float eps = 1e-5; float rgb1 = center.g;
        float N1 = eps + abs(rgb1 - chroma(pos, 0, -2, size).g); float S1 = eps + abs(rgb1 - chroma(pos, 0, 2, size).g);
        float W1 = eps + abs(rgb1 - chroma(pos, -2, 0, size).g); float E1 = eps + abs(rgb1 - chroma(pos, 2, 0, size).g);

        float rgb1mw1 = chroma(pos, 0, -1, size).g; float rgb1pw1 = chroma(pos, 0, 1, size).g;
        float rgb1m1  = chroma(pos, -1, 0, size).g; float rgb1p1  = chroma(pos, 1, 0, size).g;

        float rN = chroma(pos, 0, -1, size).r; float rS = chroma(pos, 0, 1, size).r;
        float rW = chroma(pos, -1, 0, size).r; float rE = chroma(pos, 1, 0, size).r;

        float bN = chroma(pos, 0, -1, size).b; float bS = chroma(pos, 0, 1, size).b;
        float bW = chroma(pos, -1, 0, size).b; float bE = chroma(pos, 1, 0, size).b;

        float diff_R = solve(rN, rS, rW, rE,
                             chroma(pos, 0, -3, size).r, chroma(pos, 0, 3, size).r, chroma(pos, -3, 0, size).r, chroma(pos, 3, 0, size).r,
                             N1, S1, W1, E1, rgb1mw1, rgb1pw1, rgb1m1, rgb1p1, VH_Disc);

        float diff_B = solve(bN, bS, bW, bE,
                             chroma(pos, 0, -3, size).b, chroma(pos, 0, 3, size).b, chroma(pos, -3, 0, size).b, chroma(pos, 3, 0, size).b,
                             N1, S1, W1, E1, rgb1mw1, rgb1pw1, rgb1m1, rgb1p1, VH_Disc);

        float dRN = rN - chroma(pos,  0, -1, size).g;
        float dRS = rS - chroma(pos,  0,  1, size).g;
        float dRW = rW - chroma(pos, -1,  0, size).g;
        float dRE = rE - chroma(pos,  1,  0, size).g;
        float min_dR = min(min(dRN, dRS), min(dRW, dRE));
        float max_dR = max(max(dRN, dRS), max(dRW, dRE));
        diff_R = clamp(diff_R, min_dR, max_dR);

        float dBN = bN - chroma(pos,  0, -1, size).g;
        float dBS = bS - chroma(pos,  0,  1, size).g;
        float dBW = bW - chroma(pos, -1,  0, size).g;
        float dBE = bE - chroma(pos,  1,  0, size).g;
        float min_dB = min(min(dBN, dBS), min(dBW, dBE));
        float max_dB = max(max(dBN, dBS), max(dBW, dBE));
        diff_B = clamp(diff_B, min_dB, max_dB);

        float final_R = rgb1 + diff_R;
        float final_B = rgb1 + diff_B;

        imageStore(outRGB, pos, vec4(max(vec3(final_R, rgb1, final_B), vec3(0.0)), 1.0));
    }
)glsl";

const char* finalColorPassSource = R"glsl(#version 310 es
    precision highp float; precision highp int;
    layout(local_size_x = 16, local_size_y = 16) in;

    uniform sampler2D rcdTexture;
    layout(std430, binding = 3) buffer OutBuf { float data[]; };

    uniform float rGain; uniform float bGain;
    uniform float normFactor; uniform int isPq;
    uniform mat3 colorMatrix;
    uniform float maxVal;
    const float exposure = 2.5;

    float linearToPq(float c) {
        float m1 = 2610.0 / 16384.0;
        float m2 = (2523.0 / 4096.0) * 128.0;
        float c1 = 3424.0 / 4096.0;
        float c2 = (2413.0 / 4096.0) * 32.0;
        float c3 = (2392.0 / 4096.0) * 32.0;
        float l = pow(max(c * 0.1, 1e-7), m1);
        return pow((c1 + c2 * l) / (1.0 + c3 * l), m2);
    }

    float linearToHlg(float c) {
        if (c <= 1.0 / 12.0) return sqrt(3.0 * max(c, 0.0));
        return 0.17883277 * log(max(12.0 * c - 0.28466892, 1e-7)) + 0.55991073;
    }

    #define s2(a,b) temp=a; a=min(a,b); b=max(temp,b);
    #define mx3(a,b,c) s2(b,c); s2(a,c);
    #define mn3(a,b,c) s2(a,b); s2(a,c);
    #define mnmx3(a,b,c) mx3(a,b,c); s2(a,b);

    float median9(float v[9]) {
        float temp;
        mnmx3(v[0], v[1], v[2]);
        mnmx3(v[3], v[4], v[5]);
        mnmx3(v[6], v[7], v[8]);
        mx3(v[0], v[3], v[6]);
        mnmx3(v[1], v[4], v[7]);
        mn3(v[2], v[5], v[8]);
        s2(v[1], v[6]);
        s2(v[2], v[6]);
        s2(v[1], v[4]);
        s2(v[2], v[4]);
        s2(v[4], v[7]);
        return v[4];
    }

    void main() {
        ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
        ivec2 size = textureSize(rcdTexture, 0);
        if (pos.x >= size.x || pos.y >= size.y) return;

        vec3 p00 = texelFetch(rcdTexture, clamp(pos + ivec2(-1, -1), ivec2(0), size - 1), 0).rgb;
        vec3 p10 = texelFetch(rcdTexture, clamp(pos + ivec2( 0, -1), ivec2(0), size - 1), 0).rgb;
        vec3 p20 = texelFetch(rcdTexture, clamp(pos + ivec2( 1, -1), ivec2(0), size - 1), 0).rgb;
        vec3 p01 = texelFetch(rcdTexture, clamp(pos + ivec2(-1,  0), ivec2(0), size - 1), 0).rgb;
        vec3 p11 = texelFetch(rcdTexture, pos, 0).rgb;
        vec3 p21 = texelFetch(rcdTexture, clamp(pos + ivec2( 1,  0), ivec2(0), size - 1), 0).rgb;
        vec3 p02 = texelFetch(rcdTexture, clamp(pos + ivec2(-1,  1), ivec2(0), size - 1), 0).rgb;
        vec3 p12 = texelFetch(rcdTexture, clamp(pos + ivec2( 0,  1), ivec2(0), size - 1), 0).rgb;
        vec3 p22 = texelFetch(rcdTexture, clamp(pos + ivec2( 1,  1), ivec2(0), size - 1), 0).rgb;

        vec3 wb = vec3(normFactor * exposure);

        vec3 c00 = p00 * wb; vec3 c10 = p10 * wb; vec3 c20 = p20 * wb;
        vec3 c01 = p01 * wb; vec3 c11 = p11 * wb; vec3 c21 = p21 * wb;
        vec3 c02 = p02 * wb; vec3 c12 = p12 * wb; vec3 c22 = p22 * wb;

        float cr[9];
        cr[0] = c00.r - c00.g; cr[1] = c10.r - c10.g; cr[2] = c20.r - c20.g;
        cr[3] = c01.r - c01.g; cr[4] = c11.r - c11.g; cr[5] = c21.r - c21.g;
        cr[6] = c02.r - c02.g; cr[7] = c12.r - c12.g; cr[8] = c22.r - c22.g;
        float medCr = median9(cr);

        float cb[9];
        cb[0] = c00.b - c00.g; cb[1] = c10.b - c10.g; cb[2] = c20.b - c20.g;
        cb[3] = c01.b - c01.g; cb[4] = c11.b - c11.g; cb[5] = c21.b - c21.g;
        cb[6] = c02.b - c02.g; cb[7] = c12.b - c12.g; cb[8] = c22.b - c22.g;
        float medCb = median9(cb);

        float gMin1 = min(min(c00.g, c10.g), min(c20.g, c01.g));
        float gMin2 = min(min(c21.g, c02.g), min(c12.g, c22.g));
        float gMin = min(min(gMin1, gMin2), c11.g);

        float gMax1 = max(max(c00.g, c10.g), max(c20.g, c01.g));
        float gMax2 = max(max(c21.g, c02.g), max(c12.g, c22.g));
        float gMax = max(max(gMax1, gMax2), c11.g);

        float localContrast = (gMax - gMin) / max(gMax, 1e-5);

        float darkSide = smoothstep(0.8, 0.2, c11.g / max(gMax, 1e-5));

        float fcs = smoothstep(0.3, 0.7, localContrast) * darkSide;

        medCr = mix(medCr, 0.0, fcs);
        medCb = mix(medCb, 0.0, fcs);

        vec3 rgb = vec3(medCr + c11.g, c11.g, medCb + c11.g);

        rgb = max(vec3(0.0), rgb);

        vec3 corrected = colorMatrix * rgb;
        corrected = max(vec3(0.0), corrected);

        float sensorR = p11.r / rGain;
        float sensorG = p11.g;
        float sensorB = p11.b / bGain;
        float rawMax = max(sensorR, max(sensorG, sensorB));

        float desatBlend = smoothstep(0.85, 0.98, rawMax);

        float luma = dot(corrected, vec3(0.2627, 0.6780, 0.0593));
        corrected = mix(corrected, vec3(luma), desatBlend);

        float maxChannel = max(corrected.r, max(corrected.g, corrected.b));
        float knee = 0.85;
        if (maxChannel > knee) {
            float hardLimit = 1.25;
            float range = hardLimit - knee;
            float compressedMax = knee + range * (1.0 - exp(-(maxChannel - knee) / range));
            corrected *= (compressedMax / maxChannel);
        }

        vec3 finalColor;
        if (isPq == 1)
            finalColor = vec3(linearToPq(corrected.r), linearToPq(corrected.g), linearToPq(corrected.b));
        else
            finalColor = vec3(linearToHlg(corrected.r), linearToHlg(corrected.g), linearToHlg(corrected.b));

        int idx = (pos.y * size.x + pos.x) * 4;
        data[idx]     = finalColor.r;
        data[idx + 1] = finalColor.g;
        data[idx + 2] = finalColor.b;
        data[idx + 3] = 1.0;
    }
)glsl";

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cameraw_CameraWISP_processBurstNative(
        JNIEnv* env, jobject thiz, jobjectArray framesArray,
        jbyteArray outRgbArray, jint width, jint height, jint baseBlackLevel,
        jfloat rGain, jfloat bGain, jfloat maxVal,
        jfloatArray matrixArray, jint bitDepth, jint cfaPattern,
        jfloatArray lscMapArray, jint lscMapW, jint lscMapH,
        jfloatArray blackLevelsArray, jfloatArray noiseProfilesArray) {

    int numFrames = env->GetArrayLength(framesArray);
    float matrix[9]; env->GetFloatArrayRegion(matrixArray, 0, 9, matrix);
    std::vector<float> lscMap(env->GetArrayLength(lscMapArray));
    if (!lscMap.empty()) env->GetFloatArrayRegion(lscMapArray, 0, lscMap.size(), lscMap.data());

    std::vector<float> dynBlackLevels(numFrames * 4);
    if (blackLevelsArray != nullptr) env->GetFloatArrayRegion(blackLevelsArray, 0, numFrames * 4, dynBlackLevels.data());

    std::vector<float> noiseProfiles(numFrames * 8);
    if (noiseProfilesArray != nullptr) env->GetFloatArrayRegion(noiseProfilesArray, 0, numFrames * 8, noiseProfiles.data());

    std::vector<jshortArray> localRefs(numFrames);
    std::vector<jshort*> frames(numFrames);
    for (int i = 0; i < numFrames; i++) localRefs[i] = (jshortArray)env->GetObjectArrayElement(framesArray, i);
    for (int i = 0; i < numFrames; i++) frames[i] = (jshort*)env->GetPrimitiveArrayCritical(localRefs[i], nullptr);

    int lw = width / 2; int lh = height / 2;
    std::vector<std::vector<float>> lumaPyramid(numFrames, std::vector<float>(lw * lh));
    std::vector<float> gpuRawArray(width * height * numFrames);

    int gridW = (width + 15) / 16; int gridH = (height + 15) / 16;
    std::vector<float> globalMotionMap(gridW * gridH * numFrames * 2, 0.0f);

    int numThreads = std::thread::hardware_concurrency();
    std::vector<std::thread> workers;

    auto prepWorker = [&](int f_idx) {
        int layerOffset = f_idx * (width * height);
        jshort* src = frames[f_idx];
        bool isDng = (bitDepth == 14);
        float absoluteSpikeThresh = maxVal * 0.15f;

        for (int y = 0; y < height; y++) {
            int cfaY = y % 2;
            for (int x = 0; x < width; x++) {
                int cfaX = x % 2;
                int colorChannel = (cfaY * 2) + cfaX;

                int mappedChannel = colorChannel ^ cfaPattern;

                float bl = dynBlackLevels[f_idx * 4 + mappedChannel];

                float val = (float)(src[y * width + x] & 0xFFFF) - bl;

                if (!isDng && x >= 2 && x < width - 2 && y >= 2 && y < height - 2) {
                    float nL = (float)(src[y * width + x - 2] & 0xFFFF) - bl;
                    float nR = (float)(src[y * width + x + 2] & 0xFFFF) - bl;
                    float nT = (float)(src[(y - 2) * width + x] & 0xFFFF) - bl;
                    float nB = (float)(src[(y + 2) * width + x] & 0xFFFF) - bl;

                    float maxNeighbor = std::max({nL, nR, nT, nB});
                    if (val > (maxNeighbor + absoluteSpikeThresh) && val > (maxNeighbor * 2.5f)) {
                        val = (nL + nR + nT + nB) * 0.25f;
                    }
                }

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

        for (int ch = 0; ch < 4; ch++) {
            std::swap(dynBlackLevels[ch], dynBlackLevels[bestFrameIdx * 4 + ch]);
        }
    }

    float normTo8Bit = 255.0f / (maxVal * 4.0f);

    auto motionWorker = [&](int f_idx) {
        cv::Ptr<cv::DISOpticalFlow> dis_flow = cv::DISOpticalFlow::create(cv::DISOpticalFlow::PRESET_FAST);

        cv::Mat ref8u(lh, lw, CV_8UC1);
        cv::Mat tgt8u(lh, lw, CV_8UC1);

        for (int y = 0; y < lh; y++) {
            for (int x = 0; x < lw; x++) {
                float rVal = lumaPyramid[0][y * lw + x] * normTo8Bit;
                float tVal = lumaPyramid[f_idx][y * lw + x] * normTo8Bit;
                ref8u.at<uint8_t>(y, x) = (uint8_t)std::clamp(rVal, 0.0f, 255.0f);
                tgt8u.at<uint8_t>(y, x) = (uint8_t)std::clamp(tVal, 0.0f, 255.0f);
            }
        }

        cv::medianBlur(ref8u, ref8u, 5);
        cv::medianBlur(tgt8u, tgt8u, 5);

        cv::GaussianBlur(ref8u, ref8u, cv::Size(15, 15), 4.0);
        cv::GaussianBlur(tgt8u, tgt8u, cv::Size(15, 15), 4.0);

        cv::Mat flow;
        dis_flow->calc(ref8u, tgt8u, flow);

        int layerOffset = f_idx * gridW * gridH * 2;

        for (int ty = 0; ty < gridH; ty++) {
            for (int tx = 0; tx < gridW; tx++) {
                int sampleX = std::clamp(tx * 8 + 4, 0, lw - 1);
                int sampleY = std::clamp(ty * 8 + 4, 0, lh - 1);

                cv::Vec2f motionVec = flow.at<cv::Vec2f>(sampleY, sampleX);

                float finalDx = motionVec[0] * 2.0f;
                float finalDy = motionVec[1] * 2.0f;

                int tileOffset = layerOffset + (ty * gridW + tx) * 2;
                globalMotionMap[tileOffset] = finalDx;
                globalMotionMap[tileOffset + 1] = finalDy;
            }
        }
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

    float best_ns_gr = noiseProfiles[bestFrameIdx * 8 + 2];
    float best_no_gr = noiseProfiles[bestFrameIdx * 8 + 3];
    float best_ns_gb = noiseProfiles[bestFrameIdx * 8 + 4];
    float best_no_gb = noiseProfiles[bestFrameIdx * 8 + 5];

    float finalNoiseScale = ((best_ns_gr + best_ns_gb) / 2.0f) * 1.5f;
    float finalNoiseOffset = ((best_no_gr + best_no_gb) / 2.0f) * 1.5f;

    glUniform1f(glGetUniformLocation(computeProgram, "noiseScale"), finalNoiseScale);
    glUniform1f(glGetUniformLocation(computeProgram, "noiseOffset"), finalNoiseOffset);
    glUniform1f(glGetUniformLocation(computeProgram, "rGain"), rGain);
    glUniform1f(glGetUniformLocation(computeProgram, "bGain"), bGain);
    glUniform1i(glGetUniformLocation(computeProgram, "cfaPattern"), cfaPattern);

    GLint locMergeYOffset = glGetUniformLocation(computeProgram, "yOffset");
    int slicePixelH = 256;
    for (int y = 0; y < height; y += slicePixelH) {
        int curH = std::min(slicePixelH, height - y);
        glUniform1i(locMergeYOffset, y);
        glDispatchCompute((width + 15) / 16, (curH + 15) / 16, 1);
        glFlush();
    }
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    if (bitDepth == 14) {
        const char* extSrc = R"glsl(#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(r32f, binding = 0) uniform readonly highp image2D mergedRaw;
            layout(std430, binding = 0) buffer OutBuf { uint data[]; };
            uniform vec4 uBlackLevels;
            uniform int cfaPattern;

            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(mergedRaw);
                if(pos.x >= size.x || pos.y >= size.y) return;

                int colorChannel = (pos.y & 1) * 2 + (pos.x & 1);
                int channel = colorChannel ^ cfaPattern;

                float bl = (channel == 0) ? uBlackLevels.x :
                           (channel == 1) ? uBlackLevels.y :
                           (channel == 2) ? uBlackLevels.z : uBlackLevels.w;
                float val = imageLoad(mergedRaw, pos).r;
                data[pos.y * size.x + pos.x] = uint(clamp(val + bl, 0.0, 65535.0));
            }
        )glsl";

        GLuint extShader = compileShader(GL_COMPUTE_SHADER, extSrc);
        GLuint extProg = glCreateProgram(); glAttachShader(extProg, extShader); glLinkProgram(extProg);

        GLuint ssbo; glGenBuffers(1, &ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, width * height * sizeof(uint32_t), nullptr, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);

        glUseProgram(extProg);
        glUniform4f(glGetUniformLocation(extProg, "uBlackLevels"),
                    dynBlackLevels[0], dynBlackLevels[1], dynBlackLevels[2], dynBlackLevels[3]);

        glUniform1i(glGetUniformLocation(extProg, "cfaPattern"), cfaPattern);

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
        glDeleteTextures(1, &rawBurstTex); glDeleteTextures(1, &mergedRawTex);
        glDeleteTextures(1, &motionGridTex);
        glDeleteShader(computeShader); glDeleteProgram(computeProgram);
        destroyEGL(egl);

        return result;
    } else {
        GLuint hlProg = 0;
        GLuint hlRawTex = 0;
        {
            GLuint hlShader = compileShader(GL_COMPUTE_SHADER, hlReconstructSource);
            hlProg = glCreateProgram();
            glAttachShader(hlProg, hlShader);
            glLinkProgram(hlProg);
            glDeleteShader(hlShader);

            glGenTextures(1, &hlRawTex);
            glBindTexture(GL_TEXTURE_2D, hlRawTex);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, width, height);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

            glUseProgram(hlProg);
            glBindImageTexture(0, mergedRawTex, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
            glBindImageTexture(1, hlRawTex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
            glUniform1i(glGetUniformLocation(hlProg, "cfaPattern"), cfaPattern);
            glUniform1f(glGetUniformLocation(hlProg, "rGain"), rGain);
            glUniform1f(glGetUniformLocation(hlProg, "bGain"), bGain);

            float avgBl = (dynBlackLevels[0] + dynBlackLevels[1] + dynBlackLevels[2] + dynBlackLevels[3]) * 0.25f;
            float trueMaxVal = maxVal - avgBl;

            glUniform1f(glGetUniformLocation(hlProg, "clipLevel"), trueMaxVal);

            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        auto buildCompute = [&](const char* src) {
            GLuint shader = compileShader(GL_COMPUTE_SHADER, src);
            GLuint prog = glCreateProgram(); glAttachShader(prog, shader); glLinkProgram(prog);
            glDeleteShader(shader); return prog;
        };
        GLuint progVhlpf = buildCompute(rcdVhlpfSource);
        GLuint progGreen = buildCompute(rcdGreenSource);
        GLuint progPq = buildCompute(rcdPqSource);
        GLuint progChromaBr = buildCompute(rcdChromaBrSource);
        GLuint progRgbg = buildCompute(rcdRgbgSource);

        GLuint rcdTex[5]; glGenTextures(5, rcdTex);
        auto setupTex = [&](int idx, GLenum format) {
            glBindTexture(GL_TEXTURE_2D, rcdTex[idx]);
            glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        };

        setupTex(0, GL_RGBA16F);
        setupTex(1, GL_R32F);
        setupTex(2, GL_R32F);
        setupTex(3, GL_RGBA16F);
        setupTex(4, GL_RGBA16F);
        int groupsX = (width + 15) / 16;

        auto dispatchSliced = [&](GLuint prog) {
            GLint locY = glGetUniformLocation(prog, "yOffset");
            for (int y = 0; y < height; y += slicePixelH) {
                int curH = std::min(slicePixelH, height - y);
                glUniform1i(locY, y);
                glDispatchCompute(groupsX, (curH + 15) / 16, 1);
                glFlush();
            }
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        };

        glUseProgram(progVhlpf);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, hlRawTex);
        glUniform1i(glGetUniformLocation(progVhlpf, "uRawTex"), 0);
        glBindImageTexture(0, rcdTex[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        float avgBl = (dynBlackLevels[0] + dynBlackLevels[1] + dynBlackLevels[2] + dynBlackLevels[3]) * 0.25f;
        float trueMaxVal = maxVal - avgBl;
        glUniform1f(glGetUniformLocation(progVhlpf, "uWhiteBlackRange"), trueMaxVal);
        dispatchSliced(progVhlpf);

        glUseProgram(progGreen);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, hlRawTex);
        glUniform1i(glGetUniformLocation(progGreen, "uRawTex"), 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, rcdTex[0]);
        glUniform1i(glGetUniformLocation(progGreen, "uVHLPFTex"), 1);
        glBindImageTexture(0, rcdTex[1], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glUniform1f(glGetUniformLocation(progGreen, "uWhiteBlackRange"), trueMaxVal);
        glUniform1i(glGetUniformLocation(progGreen, "cfaPattern"), cfaPattern);
        dispatchSliced(progGreen);

        glUseProgram(progPq);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, hlRawTex);
        glUniform1i(glGetUniformLocation(progPq, "uRawTex"), 0);
        glBindImageTexture(0, rcdTex[2], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glUniform1f(glGetUniformLocation(progPq, "uWhiteBlackRange"), trueMaxVal);
        dispatchSliced(progPq);

        glUseProgram(progChromaBr);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, hlRawTex); glUniform1i(glGetUniformLocation(progChromaBr, "uRawTex"), 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, rcdTex[1]); glUniform1i(glGetUniformLocation(progChromaBr, "uGreenTex"), 1);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, rcdTex[2]); glUniform1i(glGetUniformLocation(progChromaBr, "uPQTex"), 2);
        glBindImageTexture(0, rcdTex[3], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glUniform1f(glGetUniformLocation(progChromaBr, "uWhiteBlackRange"), trueMaxVal);
        glUniform1i(glGetUniformLocation(progChromaBr, "cfaPattern"), cfaPattern);
        dispatchSliced(progChromaBr);

        glUseProgram(progRgbg);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, rcdTex[3]); glUniform1i(glGetUniformLocation(progRgbg, "uChromaTex"), 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, rcdTex[0]); glUniform1i(glGetUniformLocation(progRgbg, "uVHLPFTex"), 1);
        glBindImageTexture(0, rcdTex[4], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glUniform1i(glGetUniformLocation(progRgbg, "cfaPattern"), cfaPattern);
        dispatchSliced(progRgbg);

        GLuint finalColorProg = buildCompute(finalColorPassSource);

        GLuint outputSSBO;
        glGenBuffers(1, &outputSSBO);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, width * height * 4 * sizeof(float), nullptr, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, outputSSBO);

        glUseProgram(finalColorProg);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, rcdTex[4]); glUniform1i(glGetUniformLocation(finalColorProg, "rcdTexture"), 0);
        glUniform1f(glGetUniformLocation(finalColorProg, "rGain"), rGain);
        glUniform1f(glGetUniformLocation(finalColorProg, "bGain"), bGain);
        glUniform1f(glGetUniformLocation(finalColorProg, "maxVal"), maxVal);
        float rcdNormFactor = 1.0f / std::max({maxR_gain, maxG_gain, maxB_gain});
        glUniform1f(glGetUniformLocation(finalColorProg, "normFactor"), rcdNormFactor);
        glUniform1i(glGetUniformLocation(finalColorProg, "isPq"), (bitDepth == 16) ? 1 : 0);
        float tMat[9] = { matrix[0], matrix[3], matrix[6], matrix[1], matrix[4], matrix[7], matrix[2], matrix[5], matrix[8] };
        glUniformMatrix3fv(glGetUniformLocation(finalColorProg, "colorMatrix"), 1, GL_FALSE, tMat);

        glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputSSBO);
        float* ssboPtr = (float*)glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, width * height * 4 * sizeof(float), GL_MAP_READ_BIT);
        std::vector<float> gpuRgbOutput(width * height * 4);
        if (ssboPtr) {
            memcpy(gpuRgbOutput.data(), ssboPtr, width * height * 4 * sizeof(float));
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glDeleteProgram(finalColorProg);
        glDeleteBuffers(1, &outputSSBO);

        glDeleteProgram(progVhlpf); glDeleteProgram(progGreen); glDeleteProgram(progPq);
        glDeleteProgram(progChromaBr); glDeleteProgram(progRgbg);
        glDeleteTextures(5, rcdTex);
        glDeleteTextures(1, &rawBurstTex);
        glDeleteTextures(1, &mergedRawTex);
        glDeleteTextures(1, &motionGridTex);
        glDeleteTextures(1, &hlRawTex);
        glDeleteProgram(hlProg);
        glDeleteShader(computeShader); glDeleteProgram(computeProgram);
        destroyEGL(egl);

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
                        outData[pOff] = fR >> 8; outData[pOff+1] = fR & 0xFF;
                        outData[pOff+2] = fG >> 8; outData[pOff+3] = fG & 0xFF;
                        outData[pOff+4] = fB >> 8; outData[pOff+5] = fB & 0xFF;
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
