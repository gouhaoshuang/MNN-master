# MNN Android 工程要求

## 环境

- 操作系统：Windows
- Shell：PowerShell 或命令提示符
- Python：3.10+，用于运行 skill 自带脚本
- JDK：17
- Android SDK：需要 `platform-tools`、`cmdline-tools`、`platforms/android-34`
- Android NDK：`27.0.12077973`
- CMake：`3.18.1`
- Gradle：使用工程内 wrapper，当前 wrapper 指向 `gradle-8.13-all.zip`

SDK 查找优先级：

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `D:\develop\Android\SDK`
4. `%LOCALAPPDATA%\Android\Sdk`

## 工程结构

构建根目录：

```text
project\android
```

必需文件：

```text
project\android\settings.gradle
project\android\gradle\wrapper\gradle-wrapper.jar
project\android\gradle\wrapper\gradle-wrapper.properties
```

默认构建模块：

```text
base_Yolov8nAPP
base_MobilevitAPP
PaddleOCR
opt_Yolov8nAPP
opt_MobilevitAPP
```

对应 Gradle 任务示例：

```text
:apps:base_Yolov8nAPP:assembleRelease
:apps:base_MobilevitAPP:assembleRelease
:apps:PaddleOCR:assembleRelease
:apps:opt_Yolov8nAPP:assembleRelease
:apps:opt_MobilevitAPP:assembleRelease
```

## 必需资产

当前设计假设模型和字典随源码仓库提供。大体积验证数据可以不提交到 GitHub，而是通过 `project/android/oss_assets_manifest.json` 指向公开 OSS zip 包，由 `scripts/download_oss_assets.py` 下载、校验并还原。

PaddleOCR 必需文件：

```text
apps\PaddleOCR\src\main\assets\det4_fp32.mnn
apps\PaddleOCR\src\main\assets\rec4_fp32.mnn
apps\PaddleOCR\src\main\assets\cls4_fp32.mnn
apps\PaddleOCR\src\main\assets\det4_fp16.mnn
apps\PaddleOCR\src\main\assets\rec4_fp16.mnn
apps\PaddleOCR\src\main\assets\cls4_fp16.mnn
apps\PaddleOCR\src\main\assets\ocr_keys.txt
```

YOLO 必需模型：

```text
apps\base_Yolov8nAPP\src\main\assets\yolov8n_fp32.mnn
apps\base_Yolov8nAPP\src\main\assets\yolov8n_fp16.mnn
apps\base_Yolov8nAPP\src\main\assets\yolov8n_standard_mobile_optimized.mnn
apps\opt_Yolov8nAPP\src\main\assets\yolov8n_fp32.mnn
apps\opt_Yolov8nAPP\src\main\assets\yolov8n_fp16.mnn
apps\opt_Yolov8nAPP\src\main\assets\yolov8n_standard_mobile_optimized.mnn
```

MobileViT 必需模型：

```text
apps\base_MobilevitAPP\src\main\assets\ImageNet-1k.txt
apps\base_MobilevitAPP\src\main\assets\mobilenetv2-7.mnn
apps\base_MobilevitAPP\src\main\assets\mobilevitv2_075_fp16.mnn
apps\base_MobilevitAPP\src\main\assets\mobilevitv2_075_int8.mnn
apps\base_MobilevitAPP\src\main\assets\1mobilevit2-7_fp32.mnn
apps\base_MobilevitAPP\src\main\assets\1mobilevit2-7_fp16.mnn
apps\base_MobilevitAPP\src\main\assets\1mobilevit2-7_int8.mnn
apps\base_MobilevitAPP\src\main\assets\1mobilevit_qat_eps2_int8.mnn
apps\base_MobilevitAPP\src\main\assets\mobilevit_qat_eps3_int8.mnn
apps\opt_MobilevitAPP\src\main\assets\ImageNet-1k.txt
apps\opt_MobilevitAPP\src\main\assets\mobilenetv2-7.mnn
apps\opt_MobilevitAPP\src\main\assets\mobilevitv2_075_fp16.mnn
apps\opt_MobilevitAPP\src\main\assets\mobilevitv2_075_int8.mnn
apps\opt_MobilevitAPP\src\main\assets\1mobilevit2-7_fp32.mnn
apps\opt_MobilevitAPP\src\main\assets\1mobilevit2-7_fp16.mnn
apps\opt_MobilevitAPP\src\main\assets\1mobilevit2-7_int8.mnn
apps\opt_MobilevitAPP\src\main\assets\1mobilevit_qat_eps2_int8.mnn
apps\opt_MobilevitAPP\src\main\assets\mobilevit_qat_eps3_int8.mnn
```

## APK 输出

Release 常见输出：

```text
project\android\apps\base_Yolov8nAPP\release\base_Yolov8nAPP-release.apk
project\android\apps\base_MobilevitAPP\release\base_MobilevitAPP-release.apk
project\android\apps\PaddleOCR\release\PaddleOCR-release.apk
project\android\apps\opt_Yolov8nAPP\release\opt_Yolov8nAPP-release.apk
project\android\apps\opt_MobilevitAPP\release\opt_MobilevitAPP-release.apk
```

如果这些路径不存在，脚本应回退扫描：

```text
project\android\apps\<module>\build\outputs\apk\<buildType>\*.apk
```

## 真机验证

安装前检查：

```powershell
adb devices
```

无在线设备时提示用户：

- 用 USB 连接 Android 手机
- 打开开发者选项
- 打开 USB 调试
- 接受 RSA 指纹授权弹窗
- 重新运行脚本并加 `--install`

PaddleOCR 启动日志可关注：

```text
Pipeline::Init Start
Pipeline::Init End. Dict size: 6625
```

PaddleOCR fp32/fp16 切换参考当前仓库文档 `project\android\doc\paddleocr_fp16_fp32_switch.md`；本 skill 默认不改模型名或后端精度。

## OSS 静态资产分发

GitHub 仓库应提交 `project\android\oss_assets_manifest.json`，但不提交 manifest 中列出的大型目录或压缩包。默认主流水线会在 `--download-assets auto` 模式下检测 manifest 资产是否缺失，并调用下载器补齐。

manifest 中每个资产包含：

```text
targetPath   还原到仓库内的位置
url          公共下载 URL
sha256       下载包 SHA256
sizeBytes    下载包大小
duplicateOf  内容去重关系，可为空
```

下载器要求 OSS 对象可以匿名 GET。推荐只对 `mnn-assets/android/*` 前缀开放公共读，不要开放整个 Bucket 的写权限。
