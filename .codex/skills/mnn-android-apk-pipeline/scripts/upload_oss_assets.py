#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包并上传 MNN Android 大型资产到阿里云 OSS。

凭据只从环境变量读取：
  ALIYUN_OSS_ACCESS_KEY_ID
  ALIYUN_OSS_ACCESS_KEY_SECRET
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import mimetypes
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile


DEFAULT_ASSETS = [
    ("base-yolov8n-annotations", "project/android/apps/base_Yolov8nAPP/src/main/assets/annotations", "dir"),
    ("base-yolov8n-annotations-zip", "project/android/apps/base_Yolov8nAPP/src/main/assets/annotations.zip", "file"),
    ("base-yolov8n-yolo-val", "project/android/apps/base_Yolov8nAPP/src/main/assets/yolo_val", "dir"),
    ("opt-yolov8n-annotations", "project/android/apps/opt_Yolov8nAPP/src/main/assets/annotations", "dir"),
    ("opt-yolov8n-yolo-val", "project/android/apps/opt_Yolov8nAPP/src/main/assets/yolo_val", "dir"),
    ("base-mobilevit-val-small-data", "project/android/apps/base_MobilevitAPP/src/main/assets/val_small_data", "dir"),
    ("opt-mobilevit-val-small-data", "project/android/apps/opt_MobilevitAPP/src/main/assets/val_small_data", "dir"),
    ("base-paddleocr-imgval", "project/android/apps/base_PaddleOCRAPP/src/main/assets/imgVal", "dir"),
    ("opt-paddleocr-imgval", "project/android/apps/opt_PaddleOCRAPP/src/main/assets/imgVal", "dir"),
]

OPENCV_ASSET = ("paddleocr-opencv", "project/android/apps/base_PaddleOCRAPP/OpenCV", "dir")


def log_section(title: str) -> None:
    print(f"\n== {title} ==")


def log_ok(message: str) -> None:
    print(f"[OK] {message}")


def log_warn(message: str) -> None:
    print(f"[WARN] {message}")


def repo_root_from_script() -> Path:
    # scripts/ -> mnn-android-apk-pipeline/ -> skills/ -> .codex/ -> repo root
    return Path(__file__).resolve().parents[4]


def normalize_key(value: str) -> str:
    key = value.replace("\\", "/").strip("/")
    while "//" in key:
        key = key.replace("//", "/")
    return key


def infer_region_from_endpoint(endpoint: str) -> str:
    endpoint_text = endpoint.replace("https://", "").replace("http://", "").split("/", 1)[0]
    first_label = endpoint_text.split(".", 1)[0]
    if first_label.startswith("oss-"):
        region = first_label.removeprefix("oss-")
        return region.removesuffix("-internal")
    raise ValueError(f"无法从 endpoint 推断 region，请使用标准 OSS endpoint: {endpoint}")


def find_ossutil(source_root: Path, explicit_path: str | None) -> str | None:
    candidates = []
    if explicit_path:
        candidates.append(explicit_path)

    for name in ("ossutil", "ossutil.exe", "ossutil64", "ossutil64.exe"):
        found = shutil.which(name)
        if found:
            candidates.append(found)

    tools_dir = source_root / ".codex" / "tools"
    candidates.extend(str(item) for item in tools_dir.rglob("ossutil*.exe") if item.is_file())

    for candidate in candidates:
        path = Path(candidate).expanduser()
        if path.is_file():
            return str(path)
    return None


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def directory_size(path: Path) -> int:
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def directory_fingerprint(path: Path) -> str:
    """目录指纹包含相对路径、文件大小和文件哈希，用于跨目录去重。"""
    digest = hashlib.sha256()
    files = sorted((item for item in path.rglob("*") if item.is_file()), key=lambda p: str(p.relative_to(path)).replace("\\", "/"))
    for item in files:
        rel = str(item.relative_to(path)).replace("\\", "/")
        stat = item.stat()
        digest.update(rel.encode("utf-8"))
        digest.update(b"|")
        digest.update(str(stat.st_size).encode("ascii"))
        digest.update(b"|")
        digest.update(file_sha256(item).encode("ascii"))
        digest.update(b"\n")
    return f"dir:{digest.hexdigest()}"


def asset_fingerprint(path: Path, kind: str) -> str:
    if kind == "file":
        return f"file:{file_sha256(path)}"
    return directory_fingerprint(path)


def zip_directory(source_dir: Path, zip_path: Path, force: bool) -> None:
    if zip_path.exists() and not force:
        log_ok(f"复用已有压缩包: {zip_path}")
        return
    if zip_path.exists():
        zip_path.unlink()
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for item in sorted(source_dir.rglob("*")):
            if item.is_file():
                archive.write(item, item.relative_to(source_dir))


def copy_file_as_package(source_file: Path, package_path: Path, force: bool) -> None:
    if package_path.exists() and not force:
        log_ok(f"复用已有文件包: {package_path}")
        return
    package_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_file, package_path)


def oss_signature(secret: str, string_to_sign: str) -> str:
    digest = hmac.new(secret.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    return base64.b64encode(digest).decode("ascii")


def put_oss_object(
    access_key_id: str,
    access_key_secret: str,
    bucket: str,
    endpoint: str,
    object_key: str,
    file_path: Path,
    public_read: bool,
    timeout_seconds: int,
) -> None:
    endpoint_text = endpoint.replace("https://", "").replace("http://", "").rstrip("/")
    url = f"https://{bucket}.{endpoint_text}/{object_key}"
    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    content_length = file_path.stat().st_size
    date_text = dt.datetime.now(dt.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")
    canonical_headers = ""
    headers = {
        "Date": date_text,
        "Content-Type": content_type,
        # 明确 Content-Length，避免大文件走 chunked 传输导致 OSS 或本机网络栈中止连接。
        "Content-Length": str(content_length),
    }
    if public_read:
        headers["x-oss-object-acl"] = "public-read"
        canonical_headers = "x-oss-object-acl:public-read\n"

    resource = f"/{bucket}/{object_key}"
    string_to_sign = f"PUT\n\n{content_type}\n{date_text}\n{canonical_headers}{resource}"
    signature = oss_signature(access_key_secret, string_to_sign)
    headers["Authorization"] = f"OSS {access_key_id}:{signature}"

    print(f"PUT {url}")
    with file_path.open("rb") as handle:
        request = urllib.request.Request(url, data=handle, headers=headers, method="PUT")
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                response.read()
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"OSS 上传失败 HTTP {exc.code}: {body}") from exc


def put_oss_object_with_retry(
    access_key_id: str,
    access_key_secret: str,
    bucket: str,
    endpoint: str,
    object_key: str,
    file_path: Path,
    public_read: bool,
    timeout_seconds: int,
    retries: int,
) -> None:
    for attempt in range(1, retries + 2):
        try:
            put_oss_object(
                access_key_id=access_key_id,
                access_key_secret=access_key_secret,
                bucket=bucket,
                endpoint=endpoint,
                object_key=object_key,
                file_path=file_path,
                public_read=public_read,
                timeout_seconds=timeout_seconds,
            )
            return
        except urllib.error.URLError as exc:
            if attempt > retries:
                raise
            log_warn(f"上传连接失败，准备重试 {attempt}/{retries}: {exc}")


def put_oss_object_with_ossutil(
    ossutil_path: str,
    access_key_id: str,
    access_key_secret: str,
    bucket: str,
    endpoint: str,
    object_key: str,
    file_path: Path,
    public_read: bool,
    timeout_seconds: int,
    retries: int,
    checkpoint_dir: Path,
) -> None:
    region = infer_region_from_endpoint(endpoint)
    destination = f"oss://{bucket}/{object_key}"
    command = [
        ossutil_path,
        "cp",
        str(file_path),
        destination,
        "--endpoint",
        endpoint,
        "--region",
        region,
        "--mode",
        "AK",
        "--retry-times",
        str(retries),
        "--connect-timeout",
        "30",
        "--read-timeout",
        str(timeout_seconds),
        "--bigfile-threshold",
        "64Mi",
        "--checkpoint-dir",
        str(checkpoint_dir),
        "-f",
    ]
    if public_read:
        command.extend(["--acl", "public-read"])

    env = os.environ.copy()
    env["OSS_ACCESS_KEY_ID"] = access_key_id
    env["OSS_ACCESS_KEY_SECRET"] = access_key_secret
    env["OSS_REGION"] = region

    print(f"> {ossutil_path} cp {file_path} {destination}")
    try:
        subprocess.run(command, env=env, check=True)
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(f"ossutil 上传失败，退出码 {exc.returncode}: {object_key}") from exc


def build_public_url(public_base_url: str | None, bucket: str, endpoint: str, object_key: str, archive_name: str) -> str:
    if public_base_url:
        return f"{public_base_url.rstrip('/')}/{archive_name}"
    endpoint_text = endpoint.replace("https://", "").replace("http://", "").rstrip("/")
    return f"https://{bucket}.{endpoint_text}/{object_key}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Package and upload MNN Android large assets to Alibaba Cloud OSS.")
    parser.add_argument("--source-root", default=str(repo_root_from_script()), help="仓库根目录，默认从脚本位置推断")
    parser.add_argument("--bucket", help="OSS bucket 名称")
    parser.add_argument("--endpoint", help="OSS endpoint，例如 oss-cn-hangzhou.aliyuncs.com")
    parser.add_argument("--prefix", default="mnn-assets/android", help="OSS object key 前缀")
    parser.add_argument("--stage-dir", help="本地临时打包目录")
    parser.add_argument("--manifest-path", help="输出 manifest 路径")
    parser.add_argument("--public-base-url", help="公开访问基础 URL，可使用自定义域名或 CDN")
    parser.add_argument("--dedupe-mode", choices=["content", "none"], default="content", help="是否按内容指纹去重")
    parser.add_argument("--include-opencv", action="store_true", help="同时上传 PaddleOCR OpenCV SDK")
    parser.add_argument("--public-read", action="store_true", help="上传对象时设置 public-read")
    parser.add_argument("--uploader", choices=["auto", "ossutil", "python"], default="auto", help="上传实现：优先使用 ossutil，或强制使用 python")
    parser.add_argument("--ossutil-path", help="ossutil.exe 路径；未指定时自动从 PATH 和 .codex/tools 查找")
    parser.add_argument("--ossutil-checkpoint-dir", help="ossutil 断点续传 checkpoint 目录")
    parser.add_argument("--upload-timeout", type=int, default=900, help="单个 PUT 请求超时时间，单位秒")
    parser.add_argument("--upload-retries", type=int, default=3, help="网络连接失败后的重试次数")
    parser.add_argument("--dry-run", action="store_true", help="只扫描资产，不打包、不上传、不写 manifest")
    parser.add_argument("--force", action="store_true", help="重新生成本地打包文件")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_root = Path(args.source_root).resolve()
    if not source_root.is_dir():
        raise FileNotFoundError(f"source root 不存在: {source_root}")

    stage_dir = Path(args.stage_dir).resolve() if args.stage_dir else source_root / ".codex" / "tmp" / "oss-assets"
    manifest_path = Path(args.manifest_path).resolve() if args.manifest_path else source_root / "project" / "android" / "oss_assets_manifest.json"
    checkpoint_dir = Path(args.ossutil_checkpoint_dir).resolve() if args.ossutil_checkpoint_dir else source_root / ".codex" / "tmp" / "ossutil-checkpoints"

    asset_specs = list(DEFAULT_ASSETS)
    if args.include_opencv:
        asset_specs.append(OPENCV_ASSET)

    log_section("资产扫描")
    prepared = []
    for name, relative_path, kind in asset_specs:
        full_path = source_root / relative_path
        if not full_path.exists():
            log_warn(f"跳过不存在的资产: {relative_path}")
            continue
        if kind == "dir" and not full_path.is_dir():
            raise ValueError(f"资产类型应为目录: {relative_path}")
        if kind == "file" and not full_path.is_file():
            raise ValueError(f"资产类型应为文件: {relative_path}")
        size = directory_size(full_path) if kind == "dir" else full_path.stat().st_size
        print(f"{size / 1024 / 1024:9.1f} MB  {relative_path}")
        prepared.append(
            {
                "name": name,
                "relative_path": normalize_key(relative_path),
                "kind": kind,
                "full_path": full_path,
                "size_bytes": size,
            }
        )

    if not prepared:
        raise RuntimeError("没有可上传的资产。")

    if args.dry_run:
        log_section("DryRun")
        print("仅预览资产，不打包、不上传、不写 manifest。")
        return 0

    if not args.bucket or not args.endpoint:
        raise ValueError("上传时必须提供 --bucket 和 --endpoint。")

    access_key_id = os.environ.get("ALIYUN_OSS_ACCESS_KEY_ID")
    access_key_secret = os.environ.get("ALIYUN_OSS_ACCESS_KEY_SECRET")
    if not access_key_id or not access_key_secret:
        raise RuntimeError("未找到环境变量 ALIYUN_OSS_ACCESS_KEY_ID / ALIYUN_OSS_ACCESS_KEY_SECRET。")

    ossutil_path = find_ossutil(source_root, args.ossutil_path)
    if args.uploader == "ossutil" and not ossutil_path:
        raise RuntimeError("已指定 --uploader ossutil，但没有找到 ossutil.exe。请传入 --ossutil-path。")
    use_ossutil = args.uploader == "ossutil" or (args.uploader == "auto" and ossutil_path)
    if use_ossutil:
        checkpoint_dir.mkdir(parents=True, exist_ok=True)
        log_ok(f"上传器: ossutil ({ossutil_path})")
    else:
        log_ok("上传器: python urllib")

    log_section("内容去重")
    stage_dir.mkdir(parents=True, exist_ok=True)
    prefix = normalize_key(args.prefix)
    fingerprint_to_package = {}
    manifest_assets = []

    for asset in prepared:
        if args.dedupe_mode == "content":
            print(f"计算指纹: {asset['relative_path']}")
            fingerprint = asset_fingerprint(asset["full_path"], asset["kind"])
        else:
            fingerprint = f"{asset['kind']}:{asset['relative_path']}"
        short_hash = fingerprint.split(":")[-1][:16]

        package = fingerprint_to_package.get(fingerprint)
        if package is None:
            archive_name = f"{asset['name']}-{short_hash}.zip"
            archive_path = stage_dir / archive_name
            log_section(f"打包 {asset['name']}")
            if asset["kind"] == "dir":
                zip_directory(asset["full_path"], archive_path, args.force)
            else:
                copy_file_as_package(asset["full_path"], archive_path, args.force)
            package = {
                "fingerprint": fingerprint,
                "canonical_name": asset["name"],
                "archive_path": archive_path,
                "archive_name": archive_name,
                "archive_size_bytes": archive_path.stat().st_size,
                "sha256": file_sha256(archive_path),
                "object_key": normalize_key(f"{prefix}/{archive_name}"),
            }
            fingerprint_to_package[fingerprint] = package
        else:
            log_ok(f"发现重复内容，复用 {package['archive_name']}: {asset['relative_path']}")

        manifest_assets.append(
            {
                "name": asset["name"],
                "kind": asset["kind"],
                "targetPath": asset["relative_path"],
                "archiveType": "zip",
                "objectKey": package["object_key"],
                "url": build_public_url(args.public_base_url, args.bucket, args.endpoint, package["object_key"], package["archive_name"]),
                "sha256": package["sha256"],
                "sizeBytes": package["archive_size_bytes"],
                "duplicateOf": None if package["canonical_name"] == asset["name"] else package["canonical_name"],
            }
        )

    log_section("上传 OSS")
    for package in fingerprint_to_package.values():
        print(f"{package['archive_size_bytes'] / 1024 / 1024:9.1f} MB  {package['object_key']}")
        if use_ossutil:
            put_oss_object_with_ossutil(
                ossutil_path=ossutil_path,
                access_key_id=access_key_id,
                access_key_secret=access_key_secret,
                bucket=args.bucket,
                endpoint=args.endpoint,
                object_key=package["object_key"],
                file_path=package["archive_path"],
                public_read=args.public_read,
                timeout_seconds=args.upload_timeout,
                retries=args.upload_retries,
                checkpoint_dir=checkpoint_dir,
            )
        else:
            put_oss_object_with_retry(
                access_key_id=access_key_id,
                access_key_secret=access_key_secret,
                bucket=args.bucket,
                endpoint=args.endpoint,
                object_key=package["object_key"],
                file_path=package["archive_path"],
                public_read=args.public_read,
                timeout_seconds=args.upload_timeout,
                retries=args.upload_retries,
            )
        log_ok(f"上传完成: {package['object_key']}")

    log_section("写入 manifest")
    manifest = {
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "bucket": args.bucket,
        "endpoint": args.endpoint,
        "prefix": prefix,
        "publicBaseUrl": args.public_base_url,
        "dedupeMode": args.dedupe_mode,
        "uploader": "ossutil" if use_ossutil else "python",
        "assets": manifest_assets,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    log_ok(f"manifest: {manifest_path}")
    log_ok("OSS 资产上传流程完成")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("用户中断。", file=sys.stderr)
        raise SystemExit(130)
