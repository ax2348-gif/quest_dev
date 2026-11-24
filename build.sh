#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
export JAVA_HOME="${JAVA_HOME:-/tmp/teleop_tools/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-/tmp/teleop_tools/android}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/tmp/teleop_tools/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/teleop_tools/gradle}"
if [[ ! -f app/src/main/jniLibs/arm64-v8a/libdropbearmulti.so ]]; then
    bash ./build_native.sh
fi
host_tag="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"   # linux-x86_64, or darwin-x86_64 (universal on Apple Silicon)
cc="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android28-clang"
"$cc" -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    app/src/main/cpp/quest_pty_shell.c -o app/src/main/jniLibs/arm64-v8a/libquestptyshell.so
"$cc" -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    app/src/main/cpp/quest_launch.c -o app/src/main/jniLibs/arm64-v8a/libquestlaunch.so
"$cc" -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    app/src/main/cpp/quest_app.c -o app/src/main/jniLibs/arm64-v8a/libquestapp.so
"$cc" -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    app/src/main/cpp/quest_native.c -o app/src/main/jniLibs/arm64-v8a/libquestnative.so
bash ./gradlew "-Dorg.gradle.java.home=$JAVA_HOME" --no-daemon :app:assembleRelease "$@"
python3 verify_apk.py app/build/outputs/apk/release/app-release.apk
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk artifacts/quest-dev-runtime.apk
sha256sum artifacts/quest-dev-runtime.apk
