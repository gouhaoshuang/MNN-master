# PaddleOCR fp32 / fp16 双模块对比说明

当前仓库不再通过单个 `PaddleOCR` 模块手动切换 fp32 / fp16，而是使用两个可并存安装的 Android 模块做对比：

```text
project/android/apps/base_PaddleOCRAPP
project/android/apps/opt_PaddleOCRAPP
```

## 1. 模块职责

`base_PaddleOCRAPP` 是 fp32 基准版：

- APK 包名：`com.taobao.android.base_paddleocr`
- 启动图标名：`base_paddleocr`
- 模型文件：`det4_fp32.mnn`、`rec4_fp32.mnn`、`cls4_fp32.mnn`
- MNN 后端精度：`MNN::BackendConfig::Precision_High`

`opt_PaddleOCRAPP` 是 fp16 优化版：

- APK 包名：`com.taobao.android.opt_paddleocr`
- 启动图标名：`opt_paddleocr`
- 模型文件：`det4_fp16.mnn`、`rec4_fp16.mnn`、`cls4_fp16.mnn`
- MNN 后端精度：`MNN::BackendConfig::Precision_Low`

两个模块保留相同的源码包名 `com.taobao.android.paddleocr`，这样 C++ 中的 JNI 函数名无需改动。

## 2. 关键差异文件

模型选择在两个模块各自的 `MainActivity.kt` 中：

```text
apps/base_PaddleOCRAPP/src/main/java/com/taobao/android/paddleocr/MainActivity.kt
apps/opt_PaddleOCRAPP/src/main/java/com/taobao/android/paddleocr/MainActivity.kt
```

推理精度在三个 C++ predictor 中：

```text
src/main/cpp/det_process.cc
src/main/cpp/rec_process.cc
src/main/cpp/cls_process.cc
```

base 必须保持 `Precision_High`，opt 必须保持 `Precision_Low`。不要混用成 `det fp32 + rec fp16` 或 `fp16 模型 + Precision_High`，否则对比结果不干净。

## 3. OpenCV 与验证集资产

OpenCV SDK 只保留一份：

```text
apps/base_PaddleOCRAPP/OpenCV
```

`opt_PaddleOCRAPP` 的 CMake 通过相对路径引用 base 的 OpenCV：

```cmake
set(OPENCV_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../base_PaddleOCRAPP/OpenCV/sdk/native")
```

`imgVal` 验证集不交给 Git 保管，由 `project/android/oss_assets_manifest.json` 从公开 OSS 还原到两个模块：

```text
apps/base_PaddleOCRAPP/src/main/assets/imgVal
apps/opt_PaddleOCRAPP/src/main/assets/imgVal
```

## 4. 编译

进入 Android 工程目录：

```powershell
cd D:\MNN-master\project\android
```

Debug 构建：

```powershell
& 'D:\develop\jdk\jdk-17\bin\java.exe' -classpath '.\gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon :apps:base_PaddleOCRAPP:assembleDebug :apps:opt_PaddleOCRAPP:assembleDebug --console=plain
```

产物路径：

```text
apps/base_PaddleOCRAPP/build/outputs/apk/debug/base_PaddleOCRAPP-debug.apk
apps/opt_PaddleOCRAPP/build/outputs/apk/debug/opt_PaddleOCRAPP-debug.apk
```

## 5. 安装与确认

连接手机并确认 adb 在线：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' devices -l
```

安装两个 Debug APK：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install -r --no-streaming 'D:\MNN-master\project\android\apps\base_PaddleOCRAPP\build\outputs\apk\debug\base_PaddleOCRAPP-debug.apk'
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install -r --no-streaming 'D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\build\outputs\apk\debug\opt_PaddleOCRAPP-debug.apk'
```

确认两个包可并存：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' shell pm list packages | Select-String paddleocr
```

预期包含：

```text
package:com.taobao.android.base_paddleocr
package:com.taobao.android.opt_paddleocr
```

## 6. 数据清理

两个模块的 `applicationId` 不同，App 私有目录互不影响。替换同名模型文件后，建议清理对应包的数据：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' shell pm clear com.taobao.android.base_paddleocr
& 'D:\develop\Android\SDK\platform-tools\adb.exe' shell pm clear com.taobao.android.opt_paddleocr
```
