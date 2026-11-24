#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
runtime_root="$PWD"
build_root="${QUEST_RUNTIME_BUILD_DIR:-$runtime_root/build/native-tools}"
mkdir -p "$build_root"
build_root="$(cd "$build_root" && pwd)"
ndk="${ANDROID_HOME:-/tmp/teleop_tools/android}/ndk/27.2.12479018"
host_tag="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"   # linux-x86_64, or darwin-x86_64 (universal on Apple Silicon)
toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
export CC="$toolchain/aarch64-linux-android28-clang"
export CXX="$toolchain/aarch64-linux-android28-clang++"
export AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib"
export STRIP="$toolchain/llvm-strip"
export CFLAGS="-O2 -fPIE" LDFLAGS="-Wl,-z,max-page-size=16384"
dest="$runtime_root/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$dest" "$runtime_root/artifacts"

cd "$build_root"
cp "$runtime_root/vendor/dropbear-2026.94.tar.bz2" .
cp "$runtime_root/vendor/rsync-3.5.1.tar.gz" .
shasum -a 256 -c <<'HASHES'   # shasum: on Linux and macOS alike
e098034a843699200c8c977a991fff73159735bf795d5f72ef672c41a6b1ae81  dropbear-2026.94.tar.bz2
c55f9c9dc10fb8bec397b399a0fdded53cc9a2d8e30891bb0d63724d25c37bef  rsync-3.5.1.tar.gz
HASHES
tar -xf dropbear-2026.94.tar.bz2
tar -xf rsync-3.5.1.tar.gz
python3 "$runtime_root/patch_dropbear.py" "$build_root/dropbear-2026.94"
cd dropbear-2026.94
./configure --host=aarch64-linux-android --disable-zlib --disable-syslog \
    --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx \
    --disable-lastlog --disable-shadow --disable-pam \
    ac_cv_lib_crypt_crypt=no ac_cv_func_htole64=yes
make -j4 PROGRAMS="dropbear dropbearkey" MULTI=1
cp dropbearmulti "$dest/libdropbearmulti.so"
"$STRIP" "$dest/libdropbearmulti.so"

cd "$build_root/rsync-3.5.1"
./configure --host=aarch64-linux-android --disable-openssl --disable-xxhash \
    --disable-zstd --disable-lz4 --disable-idn --disable-iconv --disable-iconv-open \
    --disable-acl-support --disable-xattr-support --disable-md2man \
    --disable-roll-simd --with-included-popt --with-included-zlib
make -j4 rsync
cp rsync "$dest/librsync.so"
"$STRIP" "$dest/librsync.so"
printf 'Native SSH and rsync binaries built.\n'
