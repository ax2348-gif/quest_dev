#!/system/bin/sh
# Enter the optional Termux package environment, preserving the runtime HOME.
set -eu
prefix=/data/data/com.termux/files/usr
if [ "${1:-}" = "--help" ]; then
    echo "Usage: quest-termux [COMMAND [ARG...]]"
    echo "No arguments opens Bash. Example: quest-termux pkg install jq"
    exit 0
fi
[ "$#" -gt 0 ] || set -- "$prefix/bin/bash" -l
if [ "${QUEST_TERMUX_ACTIVE:-}" = 1 ]; then
    # Do not nest ptrace tracers when called from an existing Termux shell.
    exec "$@"
fi
runtime_home="${HOME:?HOME is required}"
base="$runtime_home/.quest-termux"
[ -x "$base/host/bin/proot" ] && [ -f "$base/.ready" ] || {
    echo "Termux environment is not ready; run install_termux.sh first." >&2
    exit 1
}
workdir="$(pwd -P)"
export PROOT_LOADER="$base/host/libexec/proot/loader"
export PROOT_LOADER_32="$base/host/libexec/proot/loader32"
export PROOT_TMP_DIR="$base/tmp"
export LD_LIBRARY_PATH="$base/host/lib"
unset LD_PRELOAD
mkdir -p "$PROOT_TMP_DIR"
# Keep HOME/workspace at their real paths: the Android app must be able to open
# paths sent by quest-app/quest-native through its control socket.
# No fake root, no UID changes, no extra network listener.
exec "$base/host/bin/proot" --kill-on-exit -r / \
    -b "$base/guest:/data/data/com.termux" \
    -b "$base/guest:/data/user/0/com.termux" \
    -w "$workdir" /system/bin/env -u LD_LIBRARY_PATH \
    HOME="$runtime_home" QUEST_RUNTIME_HOME="$runtime_home" QUEST_TERMUX_ACTIVE=1 \
    PREFIX="$prefix" PATH="$prefix/bin:$runtime_home/bin:/system/bin" \
    TMPDIR="$prefix/tmp" SHELL="$prefix/bin/bash" TERM="${TERM:-xterm-256color}" \
    TERMUX_PKG_NO_MIRROR_SELECT=1 TERMUX_APP_PACKAGE_MANAGER=apt \
    TERMUX_MAIN_PACKAGE_FORMAT=debian "$@"
