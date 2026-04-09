cmake_minimum_required(VERSION 3.22.1)
project("cameraw")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -ftree-vectorize")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -ftree-vectorize")

set(LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/include")

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavutil.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavformat.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libswscale.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libswresample.so)

add_library(cameraw_isp SHARED
        CameraWISP.cpp
        gles_hdr_rgb.cpp
        cameraw_jni.cpp
        MLVWriter.c
        MLVDataSource.c
        MLVFrameUtils.c
        MLVReader.c
        liblj92/lj92.c
)

target_include_directories(cameraw_isp PRIVATE
        ${FFMPEG_INCLUDE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(cameraw_isp PRIVATE
        -O3
        -funsafe-math-optimizations
)

target_link_libraries(cameraw_isp
        avcodec
        avformat
        avutil
        swscale
        swresample
        GLESv3
        EGL
        android
        log
        camera2ndk
        mediandk
        nativewindow
)