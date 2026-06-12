# MNN Android 三个示例的 APK 编译与部署流程

本文记录 `base_Yolov8nAPP`、`base_MobilevitAPP`、`base_PaddleOCRAPP`、`opt_PaddleOCRAPP` 等 Android 示例在当前仓库中的可重编译流程。

## 1. 先决条件

- 已安装 Android SDK
- 已安装 JDK 17
- 已安装 NDK 27.0.12077973
- 已安装 CMake 3.18.1

本机当前路径：

- SDK: `D:\develop\Android\SDK`
- JDK: `D:\develop\jdk\jdk-17`

## 2. 需要改的仓库文件

### 2.1 接入三个模块

文件：`D:\MNN-master\project\android\settings.gradle`

保留并启用：

```gradle
include(":apps:base_MobilevitAPP")
include(":apps:base_Yolov8nAPP")
include(":apps:base_PaddleOCRAPP")
include(":apps:opt_PaddleOCRAPP")
```

### 2.2 修正 PaddleOCR 的 OpenCV 路径

文件：

```text
D:\MNN-master\project\android\apps\base_PaddleOCRAPP\src\main\cpp\CMakeLists.txt
D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\src\main\cpp\CMakeLists.txt
```

把硬编码路径改成相对路径：

```cmake
set(OPENCV_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../OpenCV/sdk/native")
```

`opt_PaddleOCRAPP` 复用 base 的 OpenCV：

```cmake
set(OPENCV_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../base_PaddleOCRAPP/OpenCV/sdk/native")
```

### 2.3 修正 PaddleOCR 的 CMake 版本

文件：

```text
D:\MNN-master\project\android\apps\base_PaddleOCRAPP\build.gradle
D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\build.gradle
```

将：

```gradle
version "3.10.2"
```

改为：

```gradle
version "3.18.1"
```

### 2.4 清理 MobileViT2 的 32 位占位库

`base_MobilevitAPP` 的 `src/main/jniLibs/armeabi-v7a` 下原先是 0 字节占位文件，会导致 `strip` 失败。

已删除这些文件，仅保留 `arm64-v8a`。

### 2.5 修正 Gradle 内存

文件：`D:\MNN-master\project\android\gradle.properties`

追加：

```properties
org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
```

## 3. 编译命令

在 `D:\MNN-master\project\android` 下执行：

```powershell
& 'D:\develop\jdk\jdk-17\bin\java.exe' -classpath 'D:\MNN-master\project\android\gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon :apps:base_Yolov8nAPP:assembleRelease :apps:base_MobilevitAPP:assembleRelease :apps:base_PaddleOCRAPP:assembleRelease :apps:opt_PaddleOCRAPP:assembleRelease --console=plain
```

## 4. 产物位置

- `D:\MNN-master\project\android\apps\base_Yolov8nAPP\release\base_Yolov8nAPP-release.apk`
- `D:\MNN-master\project\android\apps\base_MobilevitAPP\release\base_MobilevitAPP-release.apk`
- `D:\MNN-master\project\android\apps\base_PaddleOCRAPP\release\base_PaddleOCRAPP-release.apk`
- `D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\release\opt_PaddleOCRAPP-release.apk`

## 5. 安装与部署

连接手机并打开 USB 调试后执行：

```powershell
adb devices
adb install -r D:\MNN-master\project\android\apps\base_Yolov8nAPP\release\base_Yolov8nAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\base_MobilevitAPP\release\base_MobilevitAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\base_PaddleOCRAPP\release\base_PaddleOCRAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\release\opt_PaddleOCRAPP-release.apk
```

## 6. 本次构建中遇到的问题

- `PaddleOCR` 初始报错是因为 CMake 版本写死为 `3.10.2`，而本机只有 `3.18.1`
- `base_MobilevitAPP` 初始报错是因为 `armeabi-v7a` 下放了 0 字节 `.so` 占位文件
- Gradle Jetifier 在处理 `opencv-4.5.3.0.aar` 时曾出现内存不足，调大 `org.gradle.jvmargs` 后恢复正常

## 7. 本次真机部署结果

已连接设备：

```text
10.239.22.145:42179
```

实际安装命令：

```powershell
adb install -r D:\MNN-master\project\android\apps\base_Yolov8nAPP\release\base_Yolov8nAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\base_MobilevitAPP\release\base_MobilevitAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\base_PaddleOCRAPP\release\base_PaddleOCRAPP-release.apk
adb install -r D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\release\opt_PaddleOCRAPP-release.apk
```

执行结果：

- `base_Yolov8nAPP-release.apk` 安装成功
- `base_MobilevitAPP-release.apk` 安装成功
- `base_PaddleOCRAPP-release.apk` 安装成功
- `opt_PaddleOCRAPP-release.apk` 安装成功

## 8. 本次权限修复

- `base_Yolov8nAPP` 和 `base_MobilevitAPP` 现在把“选图库”和“拍照”拆开处理
- Android 13 及以上优先申请 `READ_MEDIA_IMAGES`
- 选图片时只检查图库权限，不再被相机权限卡住
- 拍照时才单独请求相机权限

## 9. PaddleOCR 运行时初始化修复

现象：

- 点击 `运行模型` 后界面显示 `测速失败: Error: Init Failed`

原因：

- `PaddleOCR` 原先把模型初始化放在启动时的相册权限判断之后
- 首次安装或权限状态变化时，权限请求结束后没有继续调用 `initModel()`
- 结果是图片可以选择，但 `Native` 层上下文 `ctx` 仍为 0，运行模型时返回 `Error: Init Failed`

修复：

- 应用启动后直接调用 `initModel()`，模型初始化不再依赖相册权限
- 点击 `选择图片` 时才检查 `READ_MEDIA_IMAGES` 或 `READ_EXTERNAL_STORAGE`
- 增加 `onRequestPermissionsResult()`，授权成功后继续打开图片选择器

验证：

```powershell
& 'D:\develop\jdk\jdk-17\bin\java.exe' -classpath 'D:\MNN-master\project\android\gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon :apps:base_PaddleOCRAPP:assembleDebug :apps:opt_PaddleOCRAPP:assembleDebug --console=plain
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install -r 'D:\MNN-master\project\android\apps\base_PaddleOCRAPP\build\outputs\apk\debug\base_PaddleOCRAPP-debug.apk'
& 'D:\develop\Android\SDK\platform-tools\adb.exe' install -r 'D:\MNN-master\project\android\apps\opt_PaddleOCRAPP\build\outputs\apk\debug\opt_PaddleOCRAPP-debug.apk'
```

启动日志中应能看到：

```text
Pipeline::Init Start
Pipeline::Init End. Dict size: 6625
```
