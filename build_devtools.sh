#!/usr/bin/env bash
# Cross-compile a small, offline-installable Android ARM64 tools bundle.
set -euo pipefail
cd -- "$(dirname -- "$0")"
runtime_root="$PWD"
build_root="${QUEST_DEVTOOLS_BUILD_DIR:-$runtime_root/build/devtools}"
mkdir -p "$build_root"
build_root="$(cd "$build_root" && pwd)"
stage="$build_root/stage"
package="$build_root/package"
toolchain="${ANDROID_HOME:-/tmp/teleop_tools/android}/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64"
export ANDROID_NDK_ROOT="${ANDROID_HOME:-/tmp/teleop_tools/android}/ndk/27.2.12479018"
export PATH="$toolchain/bin:$PATH"
export CC="$toolchain/bin/aarch64-linux-android28-clang"
export AR="$toolchain/bin/llvm-ar" RANLIB="$toolchain/bin/llvm-ranlib"
export STRIP="$toolchain/bin/llvm-strip"
export CFLAGS="-O2 -fPIC"
export LDFLAGS="-Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$stage/lib/pkgconfig"
export PKG_CONFIG_PATH="$stage/lib/pkgconfig"
target_home="/data/user/0/dev.quest.runtime/files/home"
jobs="${QUEST_BUILD_JOBS:-6}"

cd "$runtime_root/vendor"
sha256sum -c <<'HASHES'
603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a  openssl-3.5.9.tar.gz
8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5  curl-8.23.0.tar.xz
826817fd3671b8565586d6d49f688298de8354ab11db829c02720e5c74b9b39f  git-2.56.0.tar.gz
766e48423e79359ea31e41db9e5c289675947a7fcf2efdcedb726ac9d0da3784  wget-1.25.0.tar.gz
a41b5d356aea97a529fe27e0f7316d2f9d946d75927476cf9cf1b90637d00505  cacert.pem
HASHES
for archive in openssl-3.5.9.tar.gz curl-8.23.0.tar.xz git-2.56.0.tar.gz wget-1.25.0.tar.gz; do
    directory="${archive%.tar.*}"
    if [[ ! -d "$build_root/$directory" ]]; then tar -xf "$archive" -C "$build_root"; fi
done

cd "$build_root/openssl-3.5.9"
if [[ ! -f "$stage/lib/libssl.a" ]]; then
    ./Configure android-arm64 -D__ANDROID_API__=28 no-shared no-tests no-module \
        --prefix="$stage" --libdir=lib --openssldir="$target_home/devtools/etc/ssl"
    make -j"$jobs" build_libs
    make install_dev
fi

cd "$build_root/curl-8.23.0"
if [[ ! -f "$stage/lib/libcurl.a" ]]; then
    ./configure --host=aarch64-linux-android --prefix="$stage" \
        --disable-shared --enable-static --with-openssl="$stage" \
        --with-ca-bundle="$target_home/devtools/etc/cacert.pem" --without-ca-path \
        --without-libpsl --without-libidn2 --without-librtmp --without-libssh2 \
        --without-brotli --without-zstd --without-nghttp2 --without-nghttp3 \
        --without-quiche --without-ngtcp2 --disable-ldap --disable-ldaps \
        --disable-rtsp --disable-dict --disable-telnet --disable-tftp \
        --disable-smb --disable-gopher --disable-imap --disable-pop3 --disable-smtp \
        --disable-manual --disable-docs
    make -j"$jobs"
    make install
fi

cd "$build_root/git-2.56.0"
cat > config.mak <<CONFIG
CC = $CC
AR = $AR
CFLAGS = -O2 -fPIE
LDFLAGS = -pie -Wl,-z,max-page-size=16384
prefix = $target_home/devtools
SHELL_PATH = /system/bin/sh
SHELL = /bin/sh
RUNTIME_PREFIX = YesPlease
NO_GETTEXT = YesPlease
NO_ICONV = YesPlease
NO_PERL = YesPlease
NO_PYTHON = YesPlease
NO_TCLTK = YesPlease
NO_EXPAT = YesPlease
NO_REGEX = YesPlease
NO_OPENSSL = YesPlease
NO_INSTALL_HARDLINKS = YesPlease
CURL_CFLAGS = -I$stage/include
CURL_LDFLAGS = $stage/lib/libcurl.a $stage/lib/libssl.a $stage/lib/libcrypto.a -ldl -lz
CURL_CONFIG = $stage/bin/curl-config
CONFIG
make -j"$jobs" all

cd "$build_root/wget-1.25.0"
if [[ ! -f Makefile ]]; then
    CPPFLAGS="-I$stage/include" LDFLAGS="-L$stage/lib -Wl,-z,max-page-size=16384" \
    ./configure --host=aarch64-linux-android --prefix="$target_home/devtools" \
        --with-ssl=openssl --without-libpsl --disable-nls --disable-iri \
        --without-metalink --disable-pcre --disable-pcre2
fi
make -j"$jobs"

mkdir -p "$package/devtools/bin" "$package/devtools/libexec/git-core" \
    "$package/devtools/etc" "$package/devtools/share/licenses"
cp "$stage/bin/curl" "$package/devtools/bin/"
cp "$build_root/wget-1.25.0/src/wget" "$package/devtools/bin/"
cp "$build_root/git-2.56.0/git" "$package/devtools/bin/"
cp "$build_root/git-2.56.0/git-remote-http" "$package/devtools/libexec/git-core/"
ln -sfn git-remote-http "$package/devtools/libexec/git-core/git-remote-https"
cp "$runtime_root/vendor/cacert.pem" "$package/devtools/etc/"
for binary in "$package/devtools/bin/"* "$package/devtools/libexec/git-core/git-remote-http"; do "$STRIP" "$binary"; done
cp "$build_root/openssl-3.5.9/LICENSE.txt" "$package/devtools/share/licenses/OpenSSL.txt"
cp "$build_root/curl-8.23.0/COPYING" "$package/devtools/share/licenses/curl.txt"
cp "$build_root/git-2.56.0/COPYING" "$package/devtools/share/licenses/Git.txt"
cp "$build_root/wget-1.25.0/COPYING" "$package/devtools/share/licenses/Wget.txt"
printf 'curl 8.23.0\nGit 2.56.0\nWget 1.25.0\nOpenSSL 3.5.9\n' > "$package/devtools/VERSIONS.txt"
mkdir -p "$runtime_root/artifacts"
tar -czf "$runtime_root/artifacts/quest-devtools-arm64.tar.gz" -C "$package" devtools
du -h "$runtime_root/artifacts/quest-devtools-arm64.tar.gz"
