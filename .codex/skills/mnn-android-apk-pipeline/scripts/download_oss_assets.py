#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从公开 OSS URL 下载并还原 MNN Android 大型资产。"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


def log_section(title: str) -> None:
    print(f"\n== {title} ==")


def log_ok(message: str) -> None:
    print(f"[OK] {message}")


def log_warn(message: str) -> None:
    print(f"[WARN] {message}")


def repo_root_from_script() -> Path:
    # scripts/ -> mnn-android-apk-pipeline/ -> skills/ -> .codex/ -> repo root
    return Path(__file__).resolve().parents[4]


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_url(base_url: str, object_key: str) -> str:
    return f"{base_url.rstrip('/')}/{Path(object_key).name}"


def archive_url(manifest: dict, asset: dict) -> str:
    if asset.get("url"):
        return asset["url"]
    if manifest.get("publicBaseUrl"):
        return normalize_url(manifest["publicBaseUrl"], asset["objectKey"])
    endpoint = str(manifest["endpoint"]).replace("https://", "").replace("http://", "").rstrip("/")
    return f"https://{manifest['bucket']}.{endpoint}/{asset['objectKey']}"


def asset_exists(source_root: Path, asset: dict) -> bool:
    target = source_root / asset["targetPath"]
    if asset["kind"] == "file":
        return target.is_file()
    return target.is_dir() and any(target.rglob("*"))


def unique_packages(manifest: dict) -> list[dict]:
    seen = {}
    for asset in manifest["assets"]:
        key = asset["objectKey"]
        if key not in seen:
            seen[key] = {
                "objectKey": key,
                "url": archive_url(manifest, asset),
                "sha256": asset["sha256"],
                "sizeBytes": int(asset["sizeBytes"]),
            }
    return list(seen.values())


def download_file(url: str, path: Path, timeout: int, retries: int, force: bool) -> None:
    if path.exists() and not force:
        log_ok(f"复用已下载文件: {path}")
        return

    part_path = path.with_suffix(path.suffix + ".part")
    path.parent.mkdir(parents=True, exist_ok=True)

    for attempt in range(1, retries + 2):
        resume_from = part_path.stat().st_size if part_path.exists() and not force else 0
        headers = {"User-Agent": "mnn-android-apk-pipeline/1.0"}
        if resume_from > 0:
            headers["Range"] = f"bytes={resume_from}-"

        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if resume_from > 0 and response.status != 206:
                    log_warn("服务器未接受断点续传，重新下载。")
                    part_path.unlink(missing_ok=True)
                    resume_from = 0

                mode = "ab" if resume_from > 0 else "wb"
                with part_path.open(mode) as handle:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        handle.write(chunk)
            part_path.replace(path)
            return
        except urllib.error.HTTPError as exc:
            if exc.code in (403, 404):
                raise RuntimeError(f"无法下载 {url}，HTTP {exc.code}。请确认 OSS 对象已公开读或 Bucket Policy 已开放前缀。") from exc
            if attempt > retries:
                raise
            log_warn(f"下载失败，准备重试 {attempt}/{retries}: HTTP {exc.code}")
            time.sleep(min(2 * attempt, 10))
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt > retries:
                raise
            log_warn(f"下载连接失败，准备重试 {attempt}/{retries}: {exc}")
            time.sleep(min(2 * attempt, 10))


def verify_package(package: dict, path: Path) -> None:
    actual_size = path.stat().st_size
    expected_size = int(package["sizeBytes"])
    if actual_size != expected_size:
        raise RuntimeError(f"文件大小不匹配: {path}，期望 {expected_size}，实际 {actual_size}")

    actual_sha = file_sha256(path)
    if actual_sha.lower() != str(package["sha256"]).lower():
        raise RuntimeError(f"SHA256 不匹配: {path}，期望 {package['sha256']}，实际 {actual_sha}")


def safe_extract_zip(zip_path: Path, target_dir: Path, force: bool) -> None:
    if target_dir.exists() and any(target_dir.rglob("*")) and not force:
        log_ok(f"跳过已存在目录: {target_dir}")
        return
    if target_dir.exists() and force:
        shutil.rmtree(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)

    resolved_target = target_dir.resolve()
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            destination = (target_dir / member.filename).resolve()
            # 阻止 zip 中的 ../ 路径逃逸到目标目录外。
            if resolved_target not in destination.parents and destination != resolved_target:
                raise RuntimeError(f"zip 路径不安全: {member.filename}")
        archive.extractall(target_dir)


def restore_file_asset(archive_path: Path, target_path: Path, force: bool) -> None:
    if target_path.exists() and not force:
        log_ok(f"跳过已存在文件: {target_path}")
        return
    target_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(archive_path, target_path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and restore MNN Android assets from a public OSS manifest.")
    parser.add_argument("--source-root", default=str(repo_root_from_script()), help="仓库根目录")
    parser.add_argument("--manifest-path", help="OSS 资产 manifest 路径，默认 project/android/oss_assets_manifest.json")
    parser.add_argument("--cache-dir", help="下载缓存目录，默认 .codex/tmp/downloaded-oss-assets")
    parser.add_argument("--force", action="store_true", help="重新下载并覆盖已还原的资产")
    parser.add_argument("--dry-run", action="store_true", help="只展示缺失资产和下载 URL，不写文件")
    parser.add_argument("--timeout", type=int, default=300, help="单次 HTTP 请求超时时间，单位秒")
    parser.add_argument("--retries", type=int, default=3, help="下载失败后的重试次数")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_root = Path(args.source_root).resolve()
    manifest_path = Path(args.manifest_path).resolve() if args.manifest_path else source_root / "project" / "android" / "oss_assets_manifest.json"
    cache_dir = Path(args.cache_dir).resolve() if args.cache_dir else source_root / ".codex" / "tmp" / "downloaded-oss-assets"

    if not manifest_path.is_file():
        raise FileNotFoundError(f"未找到 OSS 资产 manifest: {manifest_path}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    missing_assets = [asset for asset in manifest["assets"] if not asset_exists(source_root, asset)]

    log_section("资产状态")
    if not missing_assets and not args.force:
        log_ok("manifest 中的资产都已存在")
        return 0

    for asset in missing_assets if missing_assets else manifest["assets"]:
        print(f"- {asset['name']} -> {asset['targetPath']}")

    package_manifest = dict(manifest)
    package_manifest["assets"] = missing_assets or manifest["assets"]
    packages = unique_packages(package_manifest)

    if args.dry_run:
        log_section("DryRun")
        for package in packages:
            print(f"{package['sizeBytes'] / 1024 / 1024:9.1f} MB  {package['url']}")
        return 0

    log_section("下载与校验")
    cache_dir.mkdir(parents=True, exist_ok=True)
    package_paths = {}
    for package in packages:
        archive_path = cache_dir / Path(package["objectKey"]).name
        print(f"{package['sizeBytes'] / 1024 / 1024:9.1f} MB  {package['url']}")
        download_file(package["url"], archive_path, args.timeout, args.retries, args.force)
        verify_package(package, archive_path)
        package_paths[package["objectKey"]] = archive_path
        log_ok(f"校验通过: {archive_path.name}")

    log_section("还原资产")
    for asset in missing_assets if missing_assets else manifest["assets"]:
        archive_path = package_paths[asset["objectKey"]]
        target_path = source_root / asset["targetPath"]
        if asset["kind"] == "file":
            restore_file_asset(archive_path, target_path, args.force)
        elif asset["kind"] == "dir":
            safe_extract_zip(archive_path, target_path, args.force)
        else:
            raise ValueError(f"未知资产类型: {asset['kind']}")
        log_ok(f"{asset['name']} -> {target_path}")

    log_ok("OSS 资产下载与还原完成")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("用户中断。", file=sys.stderr)
        raise SystemExit(130)
