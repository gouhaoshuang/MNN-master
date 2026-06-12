[CmdletBinding()]
param(
    [string]$RepoUrl,
    [string]$Branch = "main",
    [string]$WorkDir = (Join-Path $HOME "mnn-android-run"),
    [string]$SourceDir,
    [string]$Modules = "all",
    [ValidateSet("Release", "Debug")]
    [string]$BuildType = "Release",
    [switch]$Install,
    [switch]$SkipClone,
    [switch]$PreflightOnly,
    [switch]$NoBuild,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

$RequiredNdkVersion = "27.0.12077973"
$RequiredCmakeVersion = "3.18.1"
$RequiredCompileSdk = "34"
$DefaultModules = @(
    "base_Yolov8nAPP",
    "base_MobilevitAPP",
    "base_PaddleOCRAPP",
    "opt_Yolov8nAPP",
    "opt_MobilevitAPP",
    "opt_PaddleOCRAPP"
)

$ModulePackageNames = @{
    "base_Yolov8nAPP"    = "com.taobao.android.base_yolov8napp"
    "base_MobilevitAPP" = "com.taobao.android.base_Mobilevit"
    "base_PaddleOCRAPP" = "com.taobao.android.base_paddleocr"
    "opt_Yolov8nAPP"    = "com.taobao.android.opt_yolov8napp"
    "opt_MobilevitAPP"  = "com.taobao.android.opt_mnndemo"
    "opt_PaddleOCRAPP"  = "com.taobao.android.opt_paddleocr"
}

function Show-Usage {
    Write-Host @"
MNN Android APK Pipeline

示例：
  powershell -NoProfile -ExecutionPolicy Bypass -File .\mnn_android_pipeline.ps1 -RepoUrl "https://example.com/your/MNN-master.git" -Branch "main" -WorkDir "D:\work\mnn-android-run" -Modules "all" -BuildType "Release"

已有源码：
  powershell -NoProfile -ExecutionPolicy Bypass -File .\mnn_android_pipeline.ps1 -SkipClone -SourceDir "D:\MNN-master" -Modules "base_PaddleOCRAPP,base_Yolov8nAPP" -BuildType "Debug"

常用参数：
  -RepoUrl       新电脑 clone 源码时必填
  -Branch        Git 分支、tag 或 commit，默认 main
  -WorkDir       clone 工作目录，默认 ~/mnn-android-run
  -SourceDir     -SkipClone 时使用的源码根目录
  -Modules       all 或逗号分隔模块名
  -BuildType     Release 或 Debug
  -Install       检测到在线 adb 设备后安装 APK
  -PreflightOnly 只检查环境
  -NoBuild       跳过 Gradle 构建，只做校验和产物扫描
"@
}

if ($Help) {
    Show-Usage
    exit 0
}

if (-not $SkipClone -and -not $PreflightOnly -and [string]::IsNullOrWhiteSpace($RepoUrl)) {
    throw "RepoUrl 为必填参数。示例：-RepoUrl `"https://example.com/your/MNN-master.git`"。如果已有源码，请使用 -SkipClone -SourceDir。"
}

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "== $Title ==" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-WarnText {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Resolve-FullPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    return [System.IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path))
}

function Get-CommandPath {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    return $null
}

function Test-HttpAccess {
    param([string]$Url)
    try {
        $request = [System.Net.WebRequest]::Create($Url)
        $request.Method = "HEAD"
        $request.Timeout = 5000
        $response = $request.GetResponse()
        $response.Close()
        return $true
    } catch {
        return $false
    }
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)
    $oldErrorActionPreference = $ErrorActionPreference
    try {
        # java -version 会写 stderr；Windows PowerShell 在 Stop 模式下会把它当作错误。
        $ErrorActionPreference = "Continue"
        $output = & $JavaExe -version 2>&1
        $text = ($output | Out-String)
        if ($text -match 'version "([^"]+)"') {
            $version = $Matches[1]
            if ($version -match '^1\.(\d+)') {
                return [int]$Matches[1]
            }
            if ($version -match '^(\d+)') {
                return [int]$Matches[1]
            }
        }
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    return $null
}

function Find-AndroidSdk {
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($envName in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $value = [Environment]::GetEnvironmentVariable($envName)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $candidates.Add($value)
        }
    }
    $candidates.Add("D:\develop\Android\SDK")
    $localAppData = [Environment]::GetFolderPath("LocalApplicationData")
    if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
        $candidates.Add((Join-Path $localAppData "Android\Sdk"))
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            return (Resolve-FullPath $candidate)
        }
    }
    return $null
}

function Find-SdkManager {
    param([string]$SdkRoot)
    $patterns = @(
        "cmdline-tools\latest\bin\sdkmanager.bat",
        "cmdline-tools\bin\sdkmanager.bat",
        "tools\bin\sdkmanager.bat"
    )
    foreach ($pattern in $patterns) {
        $path = Join-Path $SdkRoot $pattern
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            return $path
        }
    }
    $cmdlineTools = Join-Path $SdkRoot "cmdline-tools"
    if (Test-Path -LiteralPath $cmdlineTools -PathType Container) {
        $found = Get-ChildItem -LiteralPath $cmdlineTools -Recurse -Filter "sdkmanager.bat" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            return $found.FullName
        }
    }
    return $null
}

function Resolve-ModuleList {
    param([string]$ModuleText)
    if ([string]::IsNullOrWhiteSpace($ModuleText) -or $ModuleText.Trim().ToLowerInvariant() -eq "all") {
        return $DefaultModules
    }

    $requested = $ModuleText.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $unknown = @($requested | Where-Object { $DefaultModules -notcontains $_ })
    if ($unknown.Count -gt 0) {
        throw "未知模块: $($unknown -join ', ')。允许值: all, $($DefaultModules -join ', ')"
    }
    return @($requested)
}

function Assert-RequiredFiles {
    param(
        [string]$Root,
        [string[]]$RelativePaths,
        [string]$ErrorPrefix
    )
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($relative in $RelativePaths) {
        $path = Join-Path $Root $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $missing.Add($relative)
        }
    }
    if ($missing.Count -gt 0) {
        Write-Fail "$ErrorPrefix 缺失以下文件："
        $missing | ForEach-Object { Write-Host "  - $_" }
        throw "资产校验失败。当前设计假设模型和数据随仓库提供，请补齐后重试。"
    }
}

function Assert-RequiredDirectories {
    param(
        [string]$Root,
        [string[]]$RelativePaths,
        [string]$ErrorPrefix
    )
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($relative in $RelativePaths) {
        $path = Join-Path $Root $relative
        if (-not (Test-Path -LiteralPath $path -PathType Container)) {
            $missing.Add($relative)
        }
    }
    if ($missing.Count -gt 0) {
        Write-Fail "$ErrorPrefix 缺失以下目录："
        $missing | ForEach-Object { Write-Host "  - $_" }
        throw "工程结构校验失败。"
    }
}

function Write-LocalProperties {
    param(
        [string]$AndroidRoot,
        [string]$SdkRoot
    )
    $path = Join-Path $AndroidRoot "local.properties"
    $escapedSdk = $SdkRoot.Replace("\", "\\")
    $lines = New-Object System.Collections.Generic.List[string]
    $sdkWritten = $false

    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $existing = Get-Content -LiteralPath $path -Encoding UTF8
        foreach ($line in $existing) {
            if ($line -match '^\s*sdk\.dir\s*=') {
                $lines.Add("sdk.dir=$escapedSdk")
                $sdkWritten = $true
            } elseif ($line -match '^\s*ndk\.dir\s*=') {
                # 不写死 ndk.dir，避免新机器路径不一致。
                continue
            } else {
                $lines.Add($line)
            }
        }
    }

    if (-not $sdkWritten) {
        $lines.Add("sdk.dir=$escapedSdk")
    }

    Set-Content -LiteralPath $path -Value $lines -Encoding UTF8
    Write-Ok "已更新 local.properties: $path"
}

function Get-ExpectedApkPath {
    param(
        [string]$AndroidRoot,
        [string]$Module,
        [string]$BuildType
    )
    $lowerType = $BuildType.ToLowerInvariant()
    $expected = Join-Path $AndroidRoot "apps\$Module\$lowerType\$Module-$lowerType.apk"
    if (Test-Path -LiteralPath $expected -PathType Leaf) {
        return $expected
    }

    $scanRoot = Join-Path $AndroidRoot "apps\$Module\build\outputs\apk\$lowerType"
    if (Test-Path -LiteralPath $scanRoot -PathType Container) {
        $found = Get-ChildItem -LiteralPath $scanRoot -Filter "*.apk" -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($found) {
            return $found.FullName
        }
    }
    return $expected
}

function Get-OnlineDevices {
    param([string]$AdbExe)
    if (-not (Test-Path -LiteralPath $AdbExe -PathType Leaf)) {
        return @()
    }
    $output = & $AdbExe devices 2>&1
    $devices = @()
    foreach ($line in $output) {
        if ($line -match '^(\S+)\s+device$') {
            $devices += $Matches[1]
        }
    }
    return $devices
}

function Invoke-CheckedCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )
    Write-Host "> $FilePath $($Arguments -join ' ')"
    $previous = Get-Location
    try {
        Set-Location -LiteralPath $WorkingDirectory
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "命令失败，退出码: $LASTEXITCODE"
        }
    } finally {
        Set-Location $previous
    }
}

Write-Section "解析参数"
$selectedModules = Resolve-ModuleList $Modules
$buildTaskSuffix = if ($BuildType -eq "Release") { "assembleRelease" } else { "assembleDebug" }
Write-Host "模块: $($selectedModules -join ', ')"
Write-Host "构建类型: $BuildType"

Write-Section "环境预检"
$preflightErrors = New-Object System.Collections.Generic.List[string]

if ($env:OS -and $env:OS -notmatch "Windows") {
    $preflightErrors.Add("当前 OS 环境变量不是 Windows_NT。")
} else {
    Write-Ok "Windows 环境检查通过"
}

Write-Host "PowerShell: $($PSVersionTable.PSVersion)"

$workRoot = Resolve-FullPath $WorkDir
$driveRoot = [System.IO.Path]::GetPathRoot($workRoot)
if ($driveRoot) {
    $driveName = $driveRoot.TrimEnd(":\")
    $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
    if ($drive) {
        $freeGb = [math]::Round($drive.Free / 1GB, 2)
        Write-Host "磁盘可用空间($driveRoot): $freeGb GB"
        if ($drive.Free -lt 10GB) {
            $preflightErrors.Add("磁盘可用空间少于 10GB：$driveRoot")
        }
    }
}

if (Test-HttpAccess "https://services.gradle.org") {
    Write-Ok "网络可访问 Gradle 服务"
} else {
    Write-WarnText "无法确认访问 https://services.gradle.org，后续 Gradle 依赖下载可能失败"
}

$gitExe = Get-CommandPath "git"
if ($gitExe) {
    Write-Ok "Git: $gitExe"
} else {
    $preflightErrors.Add("缺少 Git。建议安装 Git for Windows，或使用 winget install --id Git.Git -e")
}

$javaExe = Get-CommandPath "java"
if ($javaExe) {
    $javaMajor = Get-JavaMajorVersion $javaExe
    if ($javaMajor -eq 17) {
        Write-Ok "JDK 17: $javaExe"
    } else {
        $preflightErrors.Add("Java major version 需要 17，当前检测到: $javaMajor。请安装 JDK 17 并调整 PATH/JAVA_HOME。")
    }
} else {
    $preflightErrors.Add("缺少 java。请安装 JDK 17 并配置 PATH/JAVA_HOME。")
}

$sdkRoot = Find-AndroidSdk
if ($sdkRoot) {
    Write-Ok "Android SDK: $sdkRoot"
} else {
    $preflightErrors.Add("未找到 Android SDK。请安装 Android SDK，并设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。")
}

$adbExe = $null
$sdkManager = $null
if ($sdkRoot) {
    $adbExe = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $adbExe -PathType Leaf) {
        Write-Ok "adb: $adbExe"
    } else {
        $preflightErrors.Add("缺少 platform-tools/adb.exe。")
    }

    $sdkManager = Find-SdkManager $sdkRoot
    if ($sdkManager) {
        Write-Ok "sdkmanager: $sdkManager"
    } else {
        Write-WarnText "缺少 cmdline-tools/sdkmanager.bat；如果后续缺 Android 组件，需要先安装 Android SDK Command-line Tools。"
    }

    $platformPath = Join-Path $sdkRoot "platforms\android-$RequiredCompileSdk"
    if (Test-Path -LiteralPath $platformPath -PathType Container) {
        Write-Ok "Android platform android-$RequiredCompileSdk 已安装"
    } else {
        $preflightErrors.Add("缺少 platforms;android-$RequiredCompileSdk。")
    }

    $ndkPath = Join-Path $sdkRoot "ndk\$RequiredNdkVersion"
    if (Test-Path -LiteralPath $ndkPath -PathType Container) {
        Write-Ok "NDK $RequiredNdkVersion 已安装"
    } else {
        $preflightErrors.Add("缺少 ndk;$RequiredNdkVersion。")
    }

    $cmakePath = Join-Path $sdkRoot "cmake\$RequiredCmakeVersion"
    if (Test-Path -LiteralPath $cmakePath -PathType Container) {
        Write-Ok "CMake $RequiredCmakeVersion 已安装"
    } else {
        $preflightErrors.Add("缺少 cmake;$RequiredCmakeVersion。")
    }
}

if ($preflightErrors.Count -gt 0) {
    Write-Fail "环境预检未通过："
    $preflightErrors | ForEach-Object { Write-Host "  - $_" }
    if ($sdkManager) {
        Write-Host ""
        Write-Host "可参考以下命令安装 Android 组件："
        Write-Host "& '$sdkManager' --install `"platform-tools`" `"platforms;android-$RequiredCompileSdk`" `"ndk;$RequiredNdkVersion`" `"cmake;$RequiredCmakeVersion`""
    }
    throw "请补齐环境后重试。脚本不会静默安装依赖。"
}

if ($PreflightOnly) {
    Write-Ok "PreflightOnly 已完成"
    exit 0
}

Write-Section "准备源码"
if ($SkipClone) {
    if ([string]::IsNullOrWhiteSpace($SourceDir)) {
        $SourceDir = (Get-Location).Path
    }
    $repoRoot = Resolve-FullPath $SourceDir
    if (-not (Test-Path -LiteralPath $repoRoot -PathType Container)) {
        throw "SourceDir 不存在: $repoRoot"
    }
    Write-Ok "使用已有源码: $repoRoot"
} else {
    if ([string]::IsNullOrWhiteSpace($RepoUrl)) {
        throw "RepoUrl 为必填参数。示例：-RepoUrl `"https://example.com/your/MNN-master.git`""
    }
    New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
    $repoRoot = Join-Path $workRoot "MNN-master"
    if (Test-Path -LiteralPath $repoRoot -PathType Container) {
        Write-WarnText "目标源码目录已存在，跳过 clone: $repoRoot"
    } else {
        Invoke-CheckedCommand -FilePath $gitExe -Arguments @("clone", $RepoUrl, $repoRoot) -WorkingDirectory $workRoot
    }
    Invoke-CheckedCommand -FilePath $gitExe -Arguments @("checkout", $Branch) -WorkingDirectory $repoRoot
    Write-Ok "源码准备完成: $repoRoot"
}

$androidRoot = Join-Path $repoRoot "project\android"
if (-not (Test-Path -LiteralPath $androidRoot -PathType Container)) {
    throw "未找到 Android 构建根目录: $androidRoot"
}

Write-Section "工程结构校验"
Assert-RequiredFiles -Root $repoRoot -RelativePaths @(
    "project\android\settings.gradle",
    "project\android\gradle\wrapper\gradle-wrapper.jar",
    "project\android\gradle\wrapper\gradle-wrapper.properties"
) -ErrorPrefix "Android 工程"

$moduleDirs = $DefaultModules | ForEach-Object { "project\android\apps\$_" }
Assert-RequiredDirectories -Root $repoRoot -RelativePaths $moduleDirs -ErrorPrefix "Android 模块"
Write-Ok "工程结构校验通过"

Write-Section "资产校验"
Assert-RequiredFiles -Root $androidRoot -RelativePaths @(
    "apps\base_PaddleOCRAPP\src\main\assets\det4_fp32.mnn",
    "apps\base_PaddleOCRAPP\src\main\assets\rec4_fp32.mnn",
    "apps\base_PaddleOCRAPP\src\main\assets\cls4_fp32.mnn",
    "apps\base_PaddleOCRAPP\src\main\assets\ocr_keys.txt",
    "apps\opt_PaddleOCRAPP\src\main\assets\det4_fp16.mnn",
    "apps\opt_PaddleOCRAPP\src\main\assets\rec4_fp16.mnn",
    "apps\opt_PaddleOCRAPP\src\main\assets\cls4_fp16.mnn",
    "apps\opt_PaddleOCRAPP\src\main\assets\ocr_keys.txt"
) -ErrorPrefix "PaddleOCR base/opt"

foreach ($module in @("base_Yolov8nAPP", "opt_Yolov8nAPP")) {
    Assert-RequiredFiles -Root $androidRoot -RelativePaths @(
        "apps\$module\src\main\assets\yolov8n_fp32.mnn",
        "apps\$module\src\main\assets\yolov8n_fp16.mnn",
        "apps\$module\src\main\assets\yolov8n_standard_mobile_optimized.mnn"
    ) -ErrorPrefix $module
}

foreach ($module in @("base_MobilevitAPP", "opt_MobilevitAPP")) {
    Assert-RequiredFiles -Root $androidRoot -RelativePaths @(
        "apps\$module\src\main\assets\ImageNet-1k.txt",
        "apps\$module\src\main\assets\mobilenetv2-7.mnn",
        "apps\$module\src\main\assets\mobilevitv2_075_fp16.mnn",
        "apps\$module\src\main\assets\mobilevitv2_075_int8.mnn",
        "apps\$module\src\main\assets\1mobilevit2-7_fp32.mnn",
        "apps\$module\src\main\assets\1mobilevit2-7_fp16.mnn",
        "apps\$module\src\main\assets\1mobilevit2-7_int8.mnn",
        "apps\$module\src\main\assets\1mobilevit_qat_eps2_int8.mnn",
        "apps\$module\src\main\assets\mobilevit_qat_eps3_int8.mnn"
    ) -ErrorPrefix $module
}
Write-Ok "资产校验通过"

Write-Section "写入本机配置"
Write-LocalProperties -AndroidRoot $androidRoot -SdkRoot $sdkRoot

if (-not $NoBuild) {
    Write-Section "Gradle 构建"
    $gradleJar = Join-Path $androidRoot "gradle\wrapper\gradle-wrapper.jar"
    $tasks = @($selectedModules | ForEach-Object { ":apps:$($_):$buildTaskSuffix" })
    $gradleArgs = @(
        "-classpath",
        $gradleJar,
        "org.gradle.wrapper.GradleWrapperMain",
        "--no-daemon"
    ) + $tasks + @("--console=plain")
    Invoke-CheckedCommand -FilePath $javaExe -Arguments $gradleArgs -WorkingDirectory $androidRoot
    Write-Ok "Gradle 构建完成"
} else {
    Write-WarnText "NoBuild 已启用，跳过 Gradle 构建"
}

Write-Section "APK 产物"
$apkPaths = New-Object System.Collections.Generic.List[string]
foreach ($module in $selectedModules) {
    $apk = Get-ExpectedApkPath -AndroidRoot $androidRoot -Module $module -BuildType $BuildType
    if (Test-Path -LiteralPath $apk -PathType Leaf) {
        $apkPaths.Add($apk)
        Write-Ok "$module -> $apk"
    } else {
        Write-WarnText "$module 未找到 APK: $apk"
    }
}

Write-Section "真机检测"
$devices = @(Get-OnlineDevices -AdbExe $adbExe)
if ($devices.Count -eq 0) {
    Write-WarnText "未检测到在线 adb 设备。"
    Write-Host "请用 USB 连接 Android 手机，打开开发者选项和 USB 调试，接受 RSA 指纹授权弹窗。"
    Write-Host "连接后重新运行脚本并加 -Install 进行安装验证。"
    exit 0
}

Write-Ok "在线设备: $($devices -join ', ')"

if ($Install) {
    if ($apkPaths.Count -eq 0) {
        throw "没有可安装的 APK。"
    }
    Write-Section "安装 APK"
    foreach ($apk in $apkPaths) {
        $module = $DefaultModules | Where-Object { $apk -like "*\$_\*" } | Select-Object -First 1
        Write-Host "> $adbExe install -r --no-streaming `"$apk`""
        & $adbExe install -r --no-streaming $apk
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "安装失败: $apk"
            if ($module -and $ModulePackageNames.ContainsKey($module)) {
                Write-Host "可尝试先卸载："
                Write-Host "& '$adbExe' uninstall $($ModulePackageNames[$module])"
            }
            throw "APK 安装失败。"
        }
        Write-Ok "安装成功: $apk"
    }
} else {
    Write-Host "检测到设备但未启用 -Install，当前只完成构建和设备提示。"
}

Write-Section "完成"
Write-Ok "MNN Android APK pipeline 已完成"
