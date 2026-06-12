# PaddleOCR fp32 / fp16 切换说明

本文说明当前仓库中 `PaddleOCR` 如何在 `fp32` 和 `fp16` 两套模型之间切换，并说明为什么只改模型文件名还不等于真正启用 fp16 加速。

## 1. 当前可用模型

模型目录：

```text
D:\MNN-master\project\android\apps\PaddleOCR\src\main\assets
```

当前已有两套模型：

```text
det4_fp32.mnn
rec4_fp32.mnn
cls4_fp32.mnn

det4_fp16.mnn
rec4_fp16.mnn
cls4_fp16.mnn
```

当前没有发现 PaddleOCR 的 int8 模型文件，例如：

```text
det4_int8.mnn
rec4_int8.mnn
cls4_int8.mnn
```

所以当前仓库里 PaddleOCR 可直接切换的是 `fp32` 和 `fp16`，不是 int8。

## 2. 为什么只改文件名不够

PaddleOCR 的 Java/Kotlin 层只负责告诉 App 加载哪几个 `.mnn` 文件。位置：

```text
D:\MNN-master\project\android\apps\PaddleOCR\src\main\java\com\taobao\android\paddleocr\MainActivity.kt
```

但是实际推理 Session 是在 C++ 层创建的。当前三个 Predictor 都固定使用：

```cpp
config.type = MNN_FORWARD_CPU;
backendConfig.precision = MNN::BackendConfig::Precision_High;
backendConfig.power = MNN::BackendConfig::Power_High;
```

相关文件：

```text
D:\MNN-master\project\android\apps\PaddleOCR\src\main\cpp\det_process.cc
D:\MNN-master\project\android\apps\PaddleOCR\src\main\cpp\rec_process.cc
D:\MNN-master\project\android\apps\PaddleOCR\src\main\cpp\cls_process.cc
```

因此：

- 只把 `det4_fp32.mnn` 改成 `det4_fp16.mnn`，只是换了模型权重文件。
- 如果 C++ 运行时仍然是 `Precision_High`，实际执行仍偏向高精度路径，不一定能得到 fp16 加速。
- 要做真正的 fp16 加速实验，需要同时切换模型文件和 MNN 后端精度配置。

## 3. fp32 基准配置

### 3.1 模型文件名

在 `MainActivity.kt` 中使用：

```kotlin
private val detModelPath = "det4_fp32.mnn"
private val recModelPath = "rec4_fp32.mnn"
private val clsModelPath = "cls4_fp32.mnn"
```

### 3.2 MNN 运行时精度

在 `det_process.cc`、`rec_process.cc`、`cls_process.cc` 三个文件中保持：

```cpp
config.type = MNN_FORWARD_CPU;

MNN::BackendConfig backendConfig;
backendConfig.precision = MNN::BackendConfig::Precision_High;
backendConfig.power = MNN::BackendConfig::Power_High;
config.backendConfig = &backendConfig;
```

这套配置适合作为 fp32 基准版本，优先保证精度和稳定性。

## 4. fp16 加速配置

### 4.1 模型文件名

在 `MainActivity.kt` 中改成：

```kotlin
private val detModelPath = "det4_fp16.mnn"
private val recModelPath = "rec4_fp16.mnn"
private val clsModelPath = "cls4_fp16.mnn"
```

三类模型要保持同一版本：

- `det`：文本检测模型
- `rec`：文本识别模型
- `cls`：方向分类模型

不要混用成 `det_fp16 + rec_fp32 + cls_fp16`，除非你明确要做单项对比实验。

### 4.2 MNN 运行时精度

在 `det_process.cc`、`rec_process.cc`、`cls_process.cc` 三个文件中，把：

```cpp
backendConfig.precision = MNN::BackendConfig::Precision_High;
```

改成：

```cpp
backendConfig.precision = MNN::BackendConfig::Precision_Low;
```

完整配置示例：

```cpp
config.type = MNN_FORWARD_CPU;

MNN::BackendConfig backendConfig;
backendConfig.precision = MNN::BackendConfig::Precision_Low;
backendConfig.power = MNN::BackendConfig::Power_High;
config.backendConfig = &backendConfig;
```

这一步才是让 MNN 尝试使用低精度计算路径的关键。

## 5. 重要注意事项

`fp16` 是否一定更快，取决于手机 CPU、MNN 后端、模型算子和当前编译出的 MNN 库能力。当前代码仍然使用 `MNN_FORWARD_CPU`，MNN 会在 CPU 后端下按设备能力选择可用实现；如果设备或算子不适合 fp16，收益可能很小，甚至可能没有收益。

如果想进一步测试 GPU 路径，例如 OpenCL、Vulkan，需要确认当前 APK 链接的 MNN 动态库是否包含对应后端，并额外修改：

```cpp
config.type = MNN_FORWARD_OPENCL;
```

或其他后端类型。这个不是当前仓库 PaddleOCR 的默认配置，建议先完成 CPU + `Precision_Low` 的对比测试。

## 6. 重新编译

进入目录：

```text
D:\MNN-master\project\android
```

执行 Debug 编译：

```powershell
& 'D:\develop\jdk\jdk-17\bin\java.exe' -classpath 'D:\MNN-master\project\android\gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon :apps:PaddleOCR:assembleDebug --console=plain
```

生成 APK：

```text
D:\MNN-master\project\android\apps\PaddleOCR\build\outputs\apk\debug\PaddleOCR-debug.apk
```

## 7. 安装到手机

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install -r --no-streaming 'D:\MNN-master\project\android\apps\PaddleOCR\build\outputs\apk\debug\PaddleOCR-debug.apk'
```

如果遇到签名不同的问题，先卸载旧版本再装：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' uninstall com.taobao.android.paddleocr
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install --no-streaming 'D:\MNN-master\project\android\apps\PaddleOCR\build\outputs\apk\debug\PaddleOCR-debug.apk'
```

## 8. 是否需要清理 App 数据

当前 `PaddleOCR` 的模型复制逻辑在：

```text
D:\MNN-master\project\android\apps\PaddleOCR\src\main\java\com\taobao\android\paddleocr\Utils.kt
```

逻辑是：

```kotlin
if (!file.exists()) {
    // 从 assets 复制模型到 App 外部私有目录
}
```

因为 `fp32` 和 `fp16` 文件名不同，所以从 `fp32` 切到 `fp16` 时，一般不需要清 App 数据。应用启动后会复制新的 `*_fp16.mnn` 文件。

如果你替换了同名模型文件，例如仍然叫 `det4_fp16.mnn`，但文件内容变了，那么建议清理 App 数据或卸载重装：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' shell pm clear com.taobao.android.paddleocr
```

或：

```powershell
& 'D:\develop\Android\SDK\platform-tools\adb.exe' uninstall com.taobao.android.paddleocr
```
