cmake_minimum_required(VERSION 3.5)

# 搜索当前目录下所有 cpp 文件
file(GLOB_RECURSE CPP_SRCS ${CMAKE_CURRENT_LIST_DIR}/*.cpp )

# 创建共享库 libmnncore.so
add_library(mnncore SHARED ${CPP_SRCS})

# 指定 MNN 头文件路径 (保持原样)
include_directories(D:/AndroidStudioProjects/MNN-master/include)

# 查找系统库
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# 开启 logcat 打印
add_definitions(-DMNN_USE_LOGCAT)

# 链接指定的 MNN 库和系统库
target_link_libraries(mnncore
    "C:/Users/wm/Desktop/动态库/fp16/libMNN.so"
    ${log-lib}
    ${jnigraphics-lib}
)