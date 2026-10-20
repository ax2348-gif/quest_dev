#!/system/bin/sh
# Run inside the runtime's normal SSH/terminal shell, outside quest-termux.
set -eu
archive="${1:?Usage: sh install_termux.sh ARCHIVE SHA256}"
expected="${2:?Supply the archive SHA256}"
[ "${QUEST_TERMUX_ACTIVE:-}" != 1 ] || {
    echo "Exit quest-termux before installing its environment." >&2; exit 1;
}
case "$expected" in *[!0-9a-f]*|"") echo "Invalid SHA256" >&2; exit 2;; esac
[ "${#expected}" -eq 64 ] || exit 2
[ "$(uname -m)" = aarch64 ] || { echo "Requires ARM64 Android" >&2; exit 1; }
actual="$(sha256sum "$archive")"
[ "${actual%% *}" = "$expected" ] || { echo "Archive checksum mismatch" >&2; exit 1; }
base="$HOME/.quest-termux"
command="$HOME/bin/quest-termux"
mkdir -p "$HOME/tmp" "$HOME/bin"
if [ -e "$command" ] || [ -L "$command" ]; then
    [ -L "$command" ] && [ "$(readlink "$command")" = "../.quest-termux/quest-termux.sh" ] || {
        echo "Refusing to overwrite custom $command" >&2; exit 1;
    }
fi
if [ -e "$base" ] || [ -L "$base" ]; then
    # Never overlay a bootstrap on the user's apt database and installed tools.
    if [ -f "$base/.ready" ] && [ "$(cat "$base/.bootstrap-sha256")" = "$expected" ]; then
        ln -sfn "../.quest-termux/quest-termux.sh" "$command"
        echo "Already installed; packages and settings preserved."
        exit 0
    fi
    echo "$base already exists. Refusing to overwrite a package environment." >&2
    echo "For package updates use: quest-termux pkg upgrade" >&2
    exit 1
fi
lock="$HOME/tmp/quest-termux-install.lock"
mkdir "$lock" 2>/dev/null || {
    echo "Another install is running (or a previous one left $lock)." >&2; exit 1;
}
stage="$(mktemp -d "$HOME/tmp/quest-termux-install.XXXXXX")"
trap 'rm -rf "$stage"; rmdir "$lock"' EXIT HUP INT TERM
tar -xzf "$archive" -C "$stage"
for file in host/bin/proot host/libexec/proot/loader guest/files/usr/bin/bash quest-termux.sh; do
    [ -x "$stage/termux/$file" ] || { echo "Missing executable: $file" >&2; exit 1; }
done
mv "$stage/termux" "$base"
printf '%s\n' "$expected" > "$base/.bootstrap-sha256"
# Allow the launcher during setup; remove readiness again on failure.
touch "$base/.ready"
if ! "$base/quest-termux.sh" \
    /data/data/com.termux/files/usr/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh; then
    rm -f "$base/.ready"
    echo "Bootstrap setup failed; environment retained at $base for diagnosis." >&2
    exit 1
fi
ln -sfn "../.quest-termux/quest-termux.sh" "$command"
echo "Ready. Run quest-termux, then: pkg install jq nano openssh"
