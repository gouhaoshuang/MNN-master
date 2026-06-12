#include "Native.h"
#include "pipeline.h"
#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

// 修改点 1: 包名对应
JNIEXPORT jlong JNICALL
Java_com_taobao_android_paddleocr_Native_nativeInit(
        JNIEnv *env, jclass thiz, jstring jDetModelPath, jstring jClsModelPath,
        jstring jRecModelPath, jstring jConfigPath, jstring jLabelPath,
        jint cpuThreadNum, jstring jCPUPowerMode) {

    std::string detModelPath = jstring_to_cpp_string(env, jDetModelPath);
    std::string clsModelPath = jstring_to_cpp_string(env, jClsModelPath);
    std::string recModelPath = jstring_to_cpp_string(env, jRecModelPath);
    std::string configPath = jstring_to_cpp_string(env, jConfigPath);
    std::string labelPath = jstring_to_cpp_string(env, jLabelPath);
    std::string cpuPowerMode = jstring_to_cpp_string(env, jCPUPowerMode);

    return reinterpret_cast<jlong>(
            new Pipeline(detModelPath, clsModelPath, recModelPath, cpuPowerMode,
                         cpuThreadNum, configPath, labelPath));
}

// 修改点 2: 包名对应
JNIEXPORT jboolean JNICALL
Java_com_taobao_android_paddleocr_Native_nativeRelease(JNIEnv *env,
                                                       jclass thiz,
                                                       jlong ctx) {
    if (ctx == 0) {
        return JNI_FALSE;
    }
    Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);
    delete pipeline;
    return JNI_TRUE;
}

// 修改点 3: 包名对应 (这是新增的 ProcessPath)
JNIEXPORT jstring JNICALL
Java_com_taobao_android_paddleocr_Native_nativeProcessPath(
        JNIEnv *env, jclass thiz, jlong ctx, jstring jInputPath, jstring jSavePath) {
    if (ctx == 0) {
        return env->NewStringUTF("Error: Context Null");
    }
    std::string inputPath = jstring_to_cpp_string(env, jInputPath);
    std::string savePath = jstring_to_cpp_string(env, jSavePath);
    Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);

    // 获取 C++ 返回的时间字符串
    std::string result = pipeline->Process_Path(inputPath, savePath);

    // 转换回 Java String
    return cpp_string_to_jstring(env, result);
}
}