#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MNN Android APK 构建、资产补齐和真机安装流水线。"""

from __future__ import annotations

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path


REQUIRED_NDK_VERSION = "27.0.12077973"
REQUIRED_CMAKE_VERSION = "3.18.1"
REQUIRED_COMPILE_SDK = "34"
DEFAULT_MODULES = [
    "base_Yolov8nAPP",
    "base_MobilevitAPP",
    "PaddleOCR",
    "opt_Yolov8nAPP",
    "opt_MobilevitAPP",
]
MODULE_PACKAGE_NAMES = {
    "base_Yolov8nAPP": "com.taobao.android.base_yolov8napp",
    "base_MobilevitAPP": "com.taobao.android.base_Mobilevit",
    "PaddleOCR": "com.taobao.android.paddleocr",
    "opt_Yolov8nAPP": "com.taobao.android.opt_yolov8napp",
    "opt_MobilevitAPP": "com.taobao.android.opt_mnndemo",
}


def log_section(title: str) -> None:
    print(f"\n== {title} ==")


def log_ok(message: str) -> None:
    print(f"[OK] {message}")


def log_warn(message: str) -> None:
    print(f"[WARN] {message}")


def log_fail(message: str) -> None:
    print(f"[FAIL] {message}")


def repo_root_from_script() -> Path:
    # scripts/ -> mnn-android-apk-pipeline/ -> skills/ -> .codex/ -> repo root
    return Path(__file__).resolve().parents[4]


def command_path(name: str) -> str | None:
    return shutil.which(name)


def http_access(url: str) -> bool:
    try:
        request = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(request, timeout=5):
            return True
    except Exception:
        return False


def java_major_version(java_exe: str) -> int | None:
    try:
        result = subprocess.run([java_exe, "-version"], capture_output=True, text=True, check=False)
        text = result.stdout + result.stderr
    except OSError:
        return None

    match = re.search(r'version "([^"]+)"', text)
    if not match:
        return None
    version = match.group(1)
    old_style = re.match(r"^1\.(\d+)", version)
    if old_style:
        return int(old_style.group(1))
    modern = re.match(r"^(\d+)", version)
    return int(modern.group(1)) if modern else None


def find_android_sdk() -> Path | None:
    candidates: list[Path] = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_name)
        if value:
            candidates.append(Path(value))
    candidates.append(Path("D:/develop/Android/SDK"))
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(Path(local_app_data) / "Android" / "Sdk")

    seen = set()
    for candidate in candidates:
        resolved = candidate.expanduser()
        key = str(resolved).lower()
        if key in seen:
            continue
        seen.add(key)
        if resolved.is_dir():
            return resolved.resolve()
    return None


def find_sdkmanager(sdk_root: Path) -> Path | None:
    relative_candidates = [
        Path("cmdline-tools/latest/bin/sdkmanager.bat"),
        Path("cmdline-tools/bin/sdkmanager.bat"),
        Path("tools/bin/sdkmanager.bat"),
    ]
    for relative in relative_candidates:
        candidate = sdk_root / relative
        if candidate.is_file():
            return candidate
    cmdline_tools = sdk_root / "cmdline-tools"
    if cmdline_tools.is_dir():
        for candidate in cmdline_tools.rglob("sdkmanager.bat"):
            return candidate
    return None


def resolve_modules(module_text: str) -> list[str]:
    if not module_text or module_text.strip().lower() == "all":
        return list(DEFAULT_MODULES)
    requested = [item.strip() for item in module_text.split(",") if item.strip()]
    unknown = [item for item in requested if item not in DEFAULT_MODULES]
    if unknown:
        raise ValueError(f"未知模块: {', '.join(unknown)}。允许值: all, {', '.join(DEFAULT_MODULES)}")
    return requested


def missing_files(root: Path, relative_paths: list[str]) -> list[str]:
    return [relative for relative in relative_paths if not (root / relative).is_file()]


def missing_dirs(root: Path, relative_paths: list[str]) -> list[str]:
    return [relative for relative in relative_paths if not (root / relative).is_dir()]


def assert_files(root: Path, relative_paths: list[str], prefix: str) -> None:
    missing = missing_files(root, relative_paths)
    if missing:
        log_fail(f"{prefix} 缺失以下文件：")
        for item in missing:
            print(f"  - {item}")
        raise RuntimeError("文件校验失败。")


def assert_dirs(root: Path, relative_paths: list[str], prefix: str) -> None:
    missing = missing_dirs(root, relative_paths)
    if missing:
        log_fail(f"{prefix} 缺失以下目录：")
        for item in missing:
            print(f"  - {item}")
        raise RuntimeError("工程结构校验失败。")


def write_local_properties(android_root: Path, sdk_root: Path) -> None:
    path = android_root / "local.properties"
    escaped_sdk = str(sdk_root).replace("\\", "\\\\")
    lines: list[str] = []
    sdk_written = False

    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if re.match(r"^\s*sdk\.dir\s*=", line):
                lines.append(f"sdk.dir={escaped_sdk}")
                sdk_written = True
            elif re.match(r"^\s*ndk\.dir\s*=", line):
                # 不写死 ndk.dir，避免新机器路径不一致。
                continue
            else:
                lines.append(line)

    if not sdk_written:
        lines.append(f"sdk.dir={escaped_sdk}")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log_ok(f"已更新 local.properties: {path}")


def expected_apk_path(android_root: Path, module: str, build_type: str) -> Path:
    lower_type = build_type.lower()
    expected = android_root / "apps" / module / lower_type / f"{module}-{lower_type}.apk"
    if expected.is_file():
        return expected
    scan_root = android_root / "apps" / module / "build" / "outputs" / "apk" / lower_type
    if scan_root.is_dir():
        found = sorted(scan_root.rglob("*.apk"), key=lambda item: item.stat().st_mtime, reverse=True)
        if found:
            return found[0]
    return expected


def online_devices(adb_exe: Path) -> list[str]:
    if not adb_exe.is_file():
        return []
    result = subprocess.run([str(adb_exe), "devices"], capture_output=True, text=True, check=False)
    devices = []
    for line in result.stdout.splitlines():
        match = re.match(r"^(\S+)\s+device$", line.strip())
        if match:
            devices.append(match.group(1))
    return devices


def run_checked(command: list[str], cwd: Path) -> None:
    print("> " + " ".join(command))
    subprocess.run(command, cwd=str(cwd), check=True)


def manifest_asset_missing(source_root: Path, manifest_path: Path) -> bool:
    if not manifest_path.is_file():
        return False
    import json

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for asset in manifest.get("assets", []):
        target = source_root / asset["targetPath"]
        if asset["kind"] == "file" and not target.is_file():
            return True
        if asset["kind"] == "dir" and (not target.is_dir() or not any(target.rglob("*"))):
            return True
    return False


def restore_manifest_assets(source_root: Path, manifest_path: Path, force: bool, timeout: int, retries: int) -> None:
    script = Path(__file__).with_name("download_oss_assets.py")
    command = [
        sys.executable,
        str(script),
        "--source-root",
        str(source_root),
        "--manifest-path",
        str(manifest_path),
        "--timeout",
        str(timeout),
        "--retries",
        str(retries),
    ]
    if force:
        command.append("--force")
    run_checked(command, cwd=source_root)


def validate_required_assets(android_root: Path) -> None:
    assert_files(
        android_root,
        [
            "apps/PaddleOCR/src/main/assets/det4_fp32.mnn",
            "apps/PaddleOCR/src/main/assets/rec4_fp32.mnn",
            "apps/PaddleOCR/src/main/assets/cls4_fp32.mnn",
            "apps/PaddleOCR/src/main/assets/det4_fp16.mnn",
            "apps/PaddleOCR/src/main/assets/rec4_fp16.mnn",
            "apps/PaddleOCR/src/main/assets/cls4_fp16.mnn",
            "apps/PaddleOCR/src/main/assets/ocr_keys.txt",
        ],
        "PaddleOCR",
    )

    for module in ("base_Yolov8nAPP", "opt_Yolov8nAPP"):
        assert_files(
            android_root,
            [
                f"apps/{module}/src/main/assets/yolov8n_fp32.mnn",
                f"apps/{module}/src/main/assets/yolov8n_fp16.mnn",
                f"apps/{module}/src/main/assets/yolov8n_standard_mobile_optimized.mnn",
            ],
            module,
        )

    for module in ("base_MobilevitAPP", "opt_MobilevitAPP"):
        assert_files(
            android_root,
            [
                f"apps/{module}/src/main/assets/ImageNet-1k.txt",
                f"apps/{module}/src/main/assets/mobilenetv2-7.mnn",
                f"apps/{module}/src/main/assets/mobilevitv2_075_fp16.mnn",
                f"apps/{module}/src/main/assets/mobilevitv2_075_int8.mnn",
                f"apps/{module}/src/main/assets/1mobilevit2-7_fp32.mnn",
                f"apps/{module}/src/main/assets/1mobilevit2-7_fp16.mnn",
                f"apps/{module}/src/main/assets/1mobilevit2-7_int8.mnn",
                f"apps/{module}/src/main/assets/1mobilevit_qat_eps2_int8.mnn",
                f"apps/{module}/src/main/assets/mobilevit_qat_eps3_int8.mnn",
            ],
            module,
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MNN Android APK Pipeline")
    parser.add_argument("--repo-url", help="新机器 clone 源码时使用的 Git URL")
    parser.add_argument("--branch", help="可选的 Git 分支、tag 或 commit；不传时使用远端默认分支")
    parser.add_argument("--work-dir", default=str(Path.home() / "mnn-android-run"), help="clone 工作目录")
    parser.add_argument("--source-dir", help="--skip-clone 时使用的源码根目录")
    parser.add_argument("--modules", default="all", help="all 或逗号分隔模块名")
    parser.add_argument("--build-type", choices=["Release", "Debug"], default="Release", help="构建类型")
    parser.add_argument("--install", action="store_true", help="检测到在线 adb 设备后安装 APK")
    parser.add_argument("--skip-clone", action="store_true", help="复用已有源码")
    parser.add_argument("--preflight-only", action="store_true", help="只检查环境")
    parser.add_argument("--no-build", action="store_true", help="跳过 Gradle 构建，只做校验和产物扫描")
    parser.add_argument("--download-assets", choices=["auto", "always", "never"], default="auto", help="按 manifest 下载 OSS 静态资产")
    parser.add_argument("--asset-manifest", help="OSS 资产 manifest 路径，默认 project/android/oss_assets_manifest.json")
    parser.add_argument("--asset-force", action="store_true", help="强制重新下载并覆盖 manifest 资产")
    parser.add_argument("--asset-timeout", type=int, default=300, help="资产下载 HTTP 超时时间")
    parser.add_argument("--asset-retries", type=int, default=3, help="资产下载重试次数")
    return parser.parse_args()


def preflight(work_dir: Path) -> tuple[str, Path, Path, str]:
    log_section("环境预检")
    errors: list[str] = []

    if platform.system() != "Windows":
        errors.append(f"当前系统不是 Windows: {platform.system()}")
    else:
        log_ok("Windows 环境检查通过")

    print(f"Python: {sys.version.split()[0]}")
    usage = shutil.disk_usage(work_dir.anchor or str(work_dir.resolve().anchor))
    free_gb = round(usage.free / 1024 / 1024 / 1024, 2)
    print(f"磁盘可用空间({work_dir.anchor or work_dir.drive}): {free_gb} GB")
    if usage.free < 10 * 1024 * 1024 * 1024:
        errors.append("磁盘可用空间少于 10GB。")

    if http_access("https://services.gradle.org"):
        log_ok("网络可访问 Gradle 服务")
    else:
        log_warn("无法确认访问 https://services.gradle.org，后续 Gradle 依赖下载可能失败")

    git_exe = command_path("git")
    if git_exe:
        log_ok(f"Git: {git_exe}")
    else:
        errors.append("缺少 Git。建议安装 Git for Windows，或使用 winget install --id Git.Git -e")

    java_exe = command_path("java")
    if java_exe:
        major = java_major_version(java_exe)
        if major == 17:
            log_ok(f"JDK 17: {java_exe}")
        else:
            errors.append(f"Java major version 需要 17，当前检测到: {major}。请安装 JDK 17 并调整 PATH/JAVA_HOME。")
    else:
        errors.append("缺少 java。请安装 JDK 17 并配置 PATH/JAVA_HOME。")

    sdk_root = find_android_sdk()
    adb_exe: Path | None = None
    sdkmanager: Path | None = None
    if sdk_root:
        log_ok(f"Android SDK: {sdk_root}")
        adb_exe = sdk_root / "platform-tools" / "adb.exe"
        if adb_exe.is_file():
            log_ok(f"adb: {adb_exe}")
        else:
            errors.append("缺少 platform-tools/adb.exe。")

        sdkmanager = find_sdkmanager(sdk_root)
        if sdkmanager:
            log_ok(f"sdkmanager: {sdkmanager}")
        else:
            log_warn("缺少 cmdline-tools/sdkmanager.bat；如果后续缺 Android 组件，需要先安装 Android SDK Command-line Tools。")

        checks = [
            (sdk_root / "platforms" / f"android-{REQUIRED_COMPILE_SDK}", f"platforms;android-{REQUIRED_COMPILE_SDK}"),
            (sdk_root / "ndk" / REQUIRED_NDK_VERSION, f"ndk;{REQUIRED_NDK_VERSION}"),
            (sdk_root / "cmake" / REQUIRED_CMAKE_VERSION, f"cmake;{REQUIRED_CMAKE_VERSION}"),
        ]
        for path, label in checks:
            if path.is_dir():
                log_ok(f"{label} 已安装")
            else:
                errors.append(f"缺少 {label}。")
    else:
        errors.append("未找到 Android SDK。请安装 Android SDK，并设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。")

    if errors:
        log_fail("环境预检未通过：")
        for error in errors:
            print(f"  - {error}")
        if sdkmanager:
            print("\n可参考以下命令安装 Android 组件：")
            print(f"& '{sdkmanager}' --install \"platform-tools\" \"platforms;android-{REQUIRED_COMPILE_SDK}\" \"ndk;{REQUIRED_NDK_VERSION}\" \"cmake;{REQUIRED_CMAKE_VERSION}\"")
        raise RuntimeError("请补齐环境后重试。脚本不会静默安装依赖。")

    assert git_exe and java_exe and sdk_root and adb_exe
    return git_exe, sdk_root, adb_exe, java_exe


def main() -> int:
    args = parse_args()
    selected_modules = resolve_modules(args.modules)
    build_task_suffix = "assembleRelease" if args.build_type == "Release" else "assembleDebug"
    work_root = Path(args.work_dir).expanduser().resolve()

    log_section("解析参数")
    print(f"模块: {', '.join(selected_modules)}")
    print(f"构建类型: {args.build_type}")
    print(f"OSS 资产下载: {args.download_assets}")

    if not args.skip_clone and not args.preflight_only and not args.repo_url:
        raise ValueError("repo-url 为必填参数。如果已有源码，请使用 --skip-clone --source-dir。")

    git_exe, sdk_root, adb_exe, java_exe = preflight(work_root)
    if args.preflight_only:
        log_ok("PreflightOnly 已完成")
        return 0

    log_section("准备源码")
    if args.skip_clone:
        repo_root = Path(args.source_dir).expanduser().resolve() if args.source_dir else Path.cwd().resolve()
        if not repo_root.is_dir():
            raise FileNotFoundError(f"source-dir 不存在: {repo_root}")
        log_ok(f"使用已有源码: {repo_root}")
    else:
        work_root.mkdir(parents=True, exist_ok=True)
        repo_root = work_root / "MNN-master"
        if repo_root.is_dir():
            log_warn(f"目标源码目录已存在，跳过 clone: {repo_root}")
        else:
            run_checked([git_exe, "clone", args.repo_url, str(repo_root)], cwd=work_root)
        if args.branch:
            run_checked([git_exe, "checkout", args.branch], cwd=repo_root)
        else:
            log_ok("未指定 --branch，使用 clone 后的远端默认分支")
        log_ok(f"源码准备完成: {repo_root}")

    android_root = repo_root / "project" / "android"
    if not android_root.is_dir():
        raise FileNotFoundError(f"未找到 Android 构建根目录: {android_root}")

    log_section("工程结构校验")
    assert_files(
        repo_root,
        [
            "project/android/settings.gradle",
            "project/android/gradle/wrapper/gradle-wrapper.jar",
            "project/android/gradle/wrapper/gradle-wrapper.properties",
        ],
        "Android 工程",
    )
    assert_dirs(repo_root, [f"project/android/apps/{module}" for module in DEFAULT_MODULES], "Android 模块")
    log_ok("工程结构校验通过")

    manifest_path = Path(args.asset_manifest).expanduser().resolve() if args.asset_manifest else android_root / "oss_assets_manifest.json"
    if args.download_assets == "always" or (args.download_assets == "auto" and manifest_asset_missing(repo_root, manifest_path)):
        log_section("OSS 静态资产")
        if not manifest_path.is_file():
            raise FileNotFoundError(f"启用了资产下载，但未找到 manifest: {manifest_path}")
        restore_manifest_assets(repo_root, manifest_path, args.asset_force or args.download_assets == "always", args.asset_timeout, args.asset_retries)
    elif args.download_assets == "auto":
        log_ok("manifest 静态资产已存在或未提供 manifest，跳过下载")

    log_section("资产校验")
    validate_required_assets(android_root)
    log_ok("资产校验通过")

    log_section("写入本机配置")
    write_local_properties(android_root, sdk_root)

    if not args.no_build:
        log_section("Gradle 构建")
        gradle_jar = android_root / "gradle" / "wrapper" / "gradle-wrapper.jar"
        tasks = [f":apps:{module}:{build_task_suffix}" for module in selected_modules]
        gradle_args = [
            java_exe,
            "-classpath",
            str(gradle_jar),
            "org.gradle.wrapper.GradleWrapperMain",
            "--no-daemon",
            *tasks,
            "--console=plain",
        ]
        run_checked(gradle_args, cwd=android_root)
        log_ok("Gradle 构建完成")
    else:
        log_warn("NoBuild 已启用，跳过 Gradle 构建")

    log_section("APK 产物")
    apk_paths: list[Path] = []
    for module in selected_modules:
        apk = expected_apk_path(android_root, module, args.build_type)
        if apk.is_file():
            apk_paths.append(apk)
            log_ok(f"{module} -> {apk}")
        else:
            log_warn(f"{module} 未找到 APK: {apk}")

    log_section("真机检测")
    devices = online_devices(adb_exe)
    if not devices:
        log_warn("未检测到在线 adb 设备。")
        print("请用 USB 连接 Android 手机，打开开发者选项和 USB 调试，接受 RSA 指纹授权弹窗。")
        print("连接后重新运行脚本并加 --install 进行安装验证。")
        return 0

    log_ok(f"在线设备: {', '.join(devices)}")
    if args.install:
        if not apk_paths:
            raise RuntimeError("没有可安装的 APK。")
        unsigned_release_apks = [apk for apk in apk_paths if args.build_type == "Release" and "unsigned" in apk.name.lower()]
        if unsigned_release_apks:
            print("检测到 Release unsigned APK，Android 不能直接安装未签名 APK：")
            for apk in unsigned_release_apks:
                print(f"  - {apk}")
            raise RuntimeError("请改用 --build-type Debug --install 做真机验证，或先为 Release 配置签名。")
        log_section("安装 APK")
        for apk in apk_paths:
            run_checked([str(adb_exe), "install", "-r", "--no-streaming", str(apk)], cwd=android_root)
            module = next((item for item in DEFAULT_MODULES if item in str(apk)), None)
            if module and module in MODULE_PACKAGE_NAMES:
                print(f"如遇签名冲突，可先卸载: {adb_exe} uninstall {MODULE_PACKAGE_NAMES[module]}")
            log_ok(f"安装成功: {apk}")
    else:
        print("检测到设备但未启用 --install，当前只完成构建和设备提示。")

    log_section("完成")
    log_ok("MNN Android APK pipeline 已完成")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("用户中断。", file=sys.stderr)
        raise SystemExit(130)
