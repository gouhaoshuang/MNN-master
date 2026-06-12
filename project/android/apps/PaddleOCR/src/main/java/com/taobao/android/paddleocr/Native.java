package com.taobao.android.paddleocr;

import android.content.Context;
import android.util.Log; // 关键：必须引入这个

public class Native {
    // 关键：定义 TAG，否则 Log.e 会报错
    private static final String DEBUG_TAG = "NativeJNI";

    static {
        // 1. 加载 MNN 核心
        System.loadLibrary("MNN");

        // 2. 尝试加载 OpenCV (修复图片读取失败的关键)
        try {
            System.loadLibrary("opencv_java4");
        } catch (Throwable t) {
            Log.e(DEBUG_TAG, "Failed to load opencv_java4", t);
        }

        // 3. 加载 MNN GPU 后端 (可选)
        try {
            System.loadLibrary("MNN_CL");
            System.loadLibrary("MNN_GL");
            System.loadLibrary("MNN_Vulkan");
        } catch (Throwable t) {
            // 忽略 GPU 加载失败
        }

        // 4. 加载你编译的 C++ 库
        System.loadLibrary("Native");
    }

    private long ctx = 0;

    public boolean init(Context mContext, String det, String cls, String rec, String cfg, String label, int threads, String mode) {
        ctx = nativeInit(det, cls, rec, cfg, label, threads, mode);
        return ctx != 0;
    }

    public String processPath(String inputPath, String savePath) {
        if (ctx == 0) return "Error: Init Failed";
        return nativeProcessPath(ctx, inputPath, savePath);
    }

    public boolean release() {
        if (ctx == 0) return false;
        return nativeRelease(ctx);
    }

    // JNI 接口声明
    public static native String nativeProcessPath(long ctx, String inputPath, String savePath);

    public static native long nativeInit(String det, String cls, String rec, String cfg, String label, int threads, String mode);
    public static native boolean nativeRelease(long ctx);
}