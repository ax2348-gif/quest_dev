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
# Generators run on the build host; the compiled Git still uses Android's shell.
python3 - <<'PY'
from pathlib import Path
p=Path("Makefile")
s=p.read_text().replace("$(QUIET_GEN)$(SHELL_PATH)", "$(QUIET_GEN)$(SHELL)")
p.write_text(s)
p=Path("shared.mak")
p.write_text(p.read_text().replace('$(SHELL_PATH) "$(1)/GIT-VERSION-GEN"', '$(SHELL) "$(1)/GIT-VERSION-GEN"'))
# Bionic has no thread cancellation. Keep signal-mask handling and pthreads;
# only the unavailable cancellation-state operations are omitted.
p=Path("run-command.c")
s=p.read_text()
for call in [
    '\tCHECK_BUG(pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &as->cs),\n\t\t"disabling cancellation");',
    '\tCHECK_BUG(pthread_setcancelstate(as->cs, NULL),\n\t\t"re-enabling cancellation");',
]:
    guarded='#ifndef __ANDROID__\n'+call+'\n#endif'
    if guarded not in s:
        assert s.count(call)==1
        s=s.replace(call,guarded)
p.write_text(s)
PY
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
NO_GECOS_IN_PWENT = YesPlease
NO_ICONV = YesPlease
NO_PERL = YesPlease
NO_PYTHON = YesPlease
NO_TCLTK = YesPlease
NO_EXPAT = YesPlease
NO_REGEX = YesPlease
NO_OPENSSL = YesPlease
NO_RUST = YesPlease
PTHREAD_LIBS =
NO_INSTALL_HARDLINKS = YesPlease
CURL_CFLAGS = -I$stage/include
CURL_LDFLAGS = $stage/lib/libcurl.a $stage/lib/libssl.a $stage/lib/libcrypto.a -ldl -lz
CURL_CONFIG = $stage/bin/curl-config
CONFIG
make -j"$jobs" SHELL=/bin/sh all
make SHELL=/bin/sh DESTDIR="$build_root/git-install" install
mkdir -p "$package/devtools"
cp -a "$build_root/git-install$target_home/devtools/." "$package/devtools/"
# Keep the client workflow small; omit server/mail integration and Scalar.
for name in git-daemon git-http-backend git-imap-send git-shell scalar; do
    rm -f "$package/devtools/bin/$name" "$package/devtools/libexec/git-core/$name"
done
rm -f "$package/devtools/libexec/git-core/git"
ln -s ../../bin/git "$package/devtools/libexec/git-core/git"

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
cp "$runtime_root/vendor/cacert.pem" "$package/devtools/etc/"
printf 'ca_certificate = %s/devtools/etc/cacert.pem\n' "$target_home" > "$package/devtools/etc/wgetrc"
python3 - "$package/devtools" "$STRIP" <<'PY'
import pathlib,subprocess,sys
for p in pathlib.Path(sys.argv[1]).rglob("*"):
    if not p.is_file() or p.is_symlink():continue
    with p.open("rb") as f:elf=f.read(4)==b"\x7fELF"
    if elf:subprocess.run([sys.argv[2],str(p)],check=True)
PY
cp "$build_root/openssl-3.5.9/LICENSE.txt" "$package/devtools/share/licenses/OpenSSL.txt"
cp "$build_root/curl-8.23.0/COPYING" "$package/devtools/share/licenses/curl.txt"
cp "$build_root/git-2.56.0/COPYING" "$package/devtools/share/licenses/Git.txt"
cp "$build_root/wget-1.25.0/COPYING" "$package/devtools/share/licenses/Wget.txt"
printf 'curl 8.23.0\nGit 2.56.0\nWget 1.25.0\nOpenSSL 3.5.9\n' > "$package/devtools/VERSIONS.txt"
mkdir -p "$runtime_root/artifacts"
tar -czf "$runtime_root/artifacts/quest-devtools-arm64.tar.gz" -C "$package" devtools
du -h "$runtime_root/artifacts/quest-devtools-arm64.tar.gz"
