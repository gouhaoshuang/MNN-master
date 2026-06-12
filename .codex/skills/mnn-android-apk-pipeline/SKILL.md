---
name: mnn-android-apk-pipeline
description: 自动化并指导 Windows 上的 MNN Android 示例 APK 流水线。用于 Codex 需要检查全新 Windows Android 构建环境、clone 或复用 MNN Android 工程、从 oss_assets_manifest.json 还原公开 OSS 托管的验证资产、校验随仓库提供的模型和资产、生成 local.properties、构建 base/opt Yolov8n、MobileViT 和 PaddleOCR APK、检查 adb 设备，并可选进行真机安装验证的场景。
---

# MNN Android APK 流水线

## 概览

使用这个 skill 在 Windows 上以可复现的方式运行 MNN Android APK 流水线。优先使用 skill 自带的 Python 脚本，不要手写临时命令串。

对于新的或不熟悉的工程，在运行脚本前先阅读 `references/android_project_requirements.md`，确认所需版本、模块名、资产清单和预期 APK 输出路径。

## 快速开始

在 skill 目录下运行，或显式传入脚本路径：

```powershell
python .\scripts\mnn_android_pipeline.py `
  --repo-url "https://example.com/your/MNN-master.git" `
  --branch "main" `
  --work-dir "D:\work\mnn-android-run" `
  --modules "all" `
  --build-type "Release"
```

如果已经有本地源码，跳过 clone 并指定仓库根目录：

```powershell
python .\scripts\mnn_android_pipeline.py `
  --skip-clone `
  --source-dir "D:\MNN-master" `
  --modules "PaddleOCR,base_Yolov8nAPP" `
  --build-type "Debug"
```

连接 Android 手机后安装 APK：

```powershell
python .\scripts\mnn_android_pipeline.py `
  --skip-clone `
  --source-dir "D:\MNN-master" `
  --install
```

只还原公开 OSS 托管的静态资产，不构建 APK：

```powershell
python .\scripts\download_oss_assets.py `
  --source-root "D:\MNN-master" `
  --manifest-path "D:\MNN-master\project\android\oss_assets_manifest.json"
```

## 工作流程

1. 需要工程细节时，阅读 `references/android_project_requirements.md`。
2. 如果用户想 fresh clone 但没有提供仓库地址，询问 `--repo-url`。
3. 运行 `scripts/mnn_android_pipeline.py`。
4. 如果环境预检失败，报告缺失项和脚本给出的建议命令。不要静默安装工具。
5. 如果公开 OSS 验证资产缺失，并且存在 `project/android/oss_assets_manifest.json`，让 `mnn_android_pipeline.py` 自动还原它们。如果必需模型文件缺失，停止并说明模型文件必须随源码仓库提供，或先加入 manifest。
6. 如果没有在线 adb 设备，提示用户连接手机、启用 USB 调试、接受 RSA 授权弹窗，然后用 `--install` 重新运行。

## GitHub 与 OSS 拼接模式

这个 skill 支持把完整工程拆成两部分分发：

- GitHub 保存源码、构建脚本、skill 文件、`project/android/oss_assets_manifest.json` 和小型必需模型文件。
- OSS 保存已经被 manifest 接管的大型静态验证资产，例如 `annotations/`、`annotations.zip`、`yolo_val/`、`val_small_data/`、`imgVal/`。

发布到 GitHub 前必须确认：

1. `project/android/oss_assets_manifest.json` 已提交。
2. manifest 中的每个 `url` 可以匿名下载，且 `sha256`、`sizeBytes` 与 OSS 对象一致。
3. manifest 中的 `targetPath` 不要交给 Git 保管；这些路径应写进 `.gitignore`。
4. 不要提交 `.codex/tmp/`、`.codex/tools/`、本地 `local.properties`、APK、Gradle build 目录或 OSS 打包缓存。

其他人 clone GitHub 后，运行主流水线即可把 GitHub 代码和 OSS 静态资产拼成完整项目：

```powershell
python .\.codex\skills\mnn-android-apk-pipeline\scripts\mnn_android_pipeline.py `
  --skip-clone `
  --source-dir "D:\MNN-master"
```

当 `--download-assets auto` 检测到 manifest 资产缺失时，脚本会自动下载、校验并还原。

## OSS 资产下载

使用 `scripts/download_oss_assets.py` 从公开 OSS manifest 还原大型静态资产。脚本会对每个去重后的归档包只下载一次，校验 `sizeBytes` 和 `sha256`，再还原每个映射的 `targetPath`。

下载脚本不使用 AccessKey 凭据。如果返回 HTTP 403，提示用户将引用的 OSS 对象设置为公共读，或配置只开放对应前缀的 Bucket Policy。

## OSS 资产上传

使用 `scripts/upload_oss_assets.py` 打包大型 Android 验证资产并上传到阿里云 OSS。脚本只从环境变量读取凭据：

```powershell
$env:ALIYUN_OSS_ACCESS_KEY_ID = "..."
$env:ALIYUN_OSS_ACCESS_KEY_SECRET = "..."

python .\scripts\upload_oss_assets.py `
  --source-root "D:\MNN-master" `
  --bucket "your-bucket" `
  --endpoint "oss-cn-hangzhou.aliyuncs.com" `
  --prefix "mnn-assets/android" `
  --public-base-url "https://your-domain.example.com/mnn-assets/android"
```

不要把 AccessKey 写入文件。如果 AccessKey 暴露过，建议上传后轮换。

## 脚本契约

Python 主流水线会执行：

- 检查 Windows、Python、网络、磁盘、Git、JDK 17、Android SDK、adb、Android 34 platform、NDK 27.0.12077973 和 CMake 3.18.1。
- clone Git 仓库或复用已有 checkout。
- 当 `--download-assets auto` 检测到静态资产缺失时，从 `project/android/oss_assets_manifest.json` 还原公开 OSS 资产。
- 校验 MNN Android 模块和资产。
- 写入 `project/android/local.properties` 中的本机 `sdk.dir`。
- 使用 Gradle wrapper 构建选中的模块。
- 报告 APK 路径。
- 检查 adb 设备，并可选执行 `adb install -r --no-streaming`。

`--modules all` 的默认模块：

- `base_Yolov8nAPP`
- `base_MobilevitAPP`
- `PaddleOCR`
- `opt_Yolov8nAPP`
- `opt_MobilevitAPP`

## 备注

- `--repo-url` 不要写死；由用户在运行时提供源码仓库地址。
- 这个 skill 默认不切换 PaddleOCR 的 fp32/fp16 设置。
- `scripts/mnn_android_pipeline.ps1` 仅作为旧版参考保留；优先使用 `scripts/mnn_android_pipeline.py`。
- 这个 skill 的脚本代码注释使用中文。
