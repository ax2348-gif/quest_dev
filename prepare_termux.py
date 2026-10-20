#!/usr/bin/env python3
"""Package official ARM64 Termux binaries for Quest. No compilation required."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parent


def download(item, cache):
    name = Path(urllib.parse.urlparse(item["url"]).path).name
    dest = cache / name
    if not dest.exists():
        partial = dest.with_suffix(dest.suffix + ".partial")
        subprocess.run(
            ["curl", "--fail", "--location", "--retry", "2",
             "--proto", "=https", "--proto-redir", "=https",
             item["url"], "--output", str(partial)], check=True)
        partial.replace(dest)
    with dest.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != item["sha256"]:
        raise ValueError(f"Checksum mismatch: {dest}; remove it before retrying")
    return dest


def extract_bootstrap(archive, prefix):
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            name = PurePosixPath(item.filename)
            if name.is_absolute() or ".." in name.parts:
                raise ValueError(f"Unsafe bootstrap member: {name}")
            z.extract(item, prefix)
            mode = (item.external_attr >> 16) & 0o777
            if mode:
                (prefix / name).chmod(mode)
    # Termux's bootstrap zip stores symlinks in a separate manifest.
    for line in (prefix / "SYMLINKS.txt").read_text().splitlines():
        target, relative = line.split("←", 1)
        name = PurePosixPath(relative)
        if name.is_absolute() or ".." in name.parts:
            raise ValueError(f"Unsafe symlink name: {name}")
        (prefix / name).symlink_to(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "artifacts/termux-cache")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "artifacts/quest-termux-aarch64.tar.gz")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / "termux/sources.lock.json").read_text())
    bootstrap = download(manifest["bootstrap"], args.cache)
    packages = [download(p, args.cache) for p in manifest["host_packages"]]
    with tempfile.TemporaryDirectory(prefix="quest-termux-") as temp:
        stage = Path(temp)
        bundle = stage / "termux"
        prefix = bundle / "guest/files/usr"
        prefix.mkdir(parents=True)
        extract_bootstrap(bootstrap, prefix)
        for path in ("guest/cache", "guest/files/home", "guest/files/usr/tmp", "tmp"):
            (bundle / path).mkdir(parents=True, exist_ok=True)
        # PRoot and its loader stay outside the mutable package environment.
        # An apt upgrade must never replace the active host-side tracer.
        extracted = stage / "host-packages"
        for package in packages:
            subprocess.run(["dpkg-deb", "--extract", str(package), str(extracted)],
                           check=True)
        shutil.copytree(extracted / "data/data/com.termux/files/usr",
                        bundle / "host", symlinks=True)
        shutil.copy2(ROOT / "termux/quest-termux.sh", bundle / "quest-termux.sh")
        (bundle / "quest-termux.sh").chmod(0o755)
        shutil.copy2(ROOT / "termux/sources.lock.json", bundle / "sources.lock.json")
        # Canonical timestamps/owners make repeated builds byte-for-byte equal.
        with args.output.open("wb") as output:
            with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as gz:
                with tarfile.open(fileobj=gz, mode="w") as tar:
                    for path in [bundle, *sorted(bundle.rglob("*"))]:
                        info = tar.gettarinfo(str(path), str(path.relative_to(stage)))
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        info.mtime = 0
                        if info.isfile():
                            with path.open("rb") as source:
                                tar.addfile(info, source)
                        else:
                            tar.addfile(info)
    with args.output.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    args.output.with_suffix(args.output.suffix + ".sha256").write_text(
        f"{digest}  {args.output.name}\n")
    print(f"{digest}  {args.output}")


if __name__ == "__main__":
    main()
