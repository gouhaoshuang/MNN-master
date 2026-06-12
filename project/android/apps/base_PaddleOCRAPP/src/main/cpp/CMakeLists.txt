cmake_minimum_required(VERSION 3.4.1)

# ==========================================
# 1. 基础编译参数
# ==========================================
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -O3 -frtti -fexceptions")

# ==========================================
# 2. 配置 OpenCV (手动指定)
# ==========================================
set(OPENCV_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../OpenCV/sdk/native")
include_directories(${OPENCV_ROOT}/jni/include)
add_library(libopencv_java4 SHARED IMPORTED)
set_target_properties(libopencv_java4 PROPERTIES IMPORTED_LOCATION
        ${OPENCV_ROOT}/libs/${ANDROID_ABI}/libopencv_java4.so)

# ==========================================
# 3. 配置 MNN
# ==========================================
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so)

# ==========================================
# 4. 系统库 (修复 glReadPixels 等报错)
# ==========================================
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
# 【新增】必须查找 OpenGL 和 EGL 库
find_library(glesv2-lib GLESv2)
find_library(egl-lib EGL)

# ==========================================
# 5. 编译 Native 库
# ==========================================
add_library(
        Native
        SHARED
        Native.cc
        pipeline.cc
        det_process.cc
        cls_process.cc
        rec_process.cc
        utils.cc
        # 【新增】这两个文件必须编译，否则 BoxesFromBitmap 报错
        db_post_process.cc
        clipper.cpp
)

# ==========================================
# 6. 链接
# ==========================================
target_link_libraries(
        Native
        MNN
        libopencv_java4
        ${log-lib}
        ${jnigraphics-lib}
        # 【新增】链接 OpenGL 库
        ${glesv2-lib}
        ${egl-lib}
)
