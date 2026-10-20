#!/system/bin/sh
# Run in the normal runtime shell after pkg install jq nano openssh.
set -eu
normal_home="$HOME"
normal_cwd="$(pwd -P)"
quest-termux bash -c '
    set -eu
    test "$HOME" = "$1"
    test "$(pwd -P)" = "$2"
    test "$3" = "two words"
    test "$4" = ""
    test "$(quest-termux bash -c "echo nested-ok")" = nested-ok
    printf "{\"value\":21}\n" | jq -e ".value * 2 == 42"
    nano --version | head -1
    ssh -V
    git --version
    quest-native status
    quest-app list
    apt-get check
    curl -fsSL https://example.com -o "$TMPDIR/quest-termux-network-check.html"
    test -s "$TMPDIR/quest-termux-network-check.html"
    rm "$TMPDIR/quest-termux-network-check.html"
' smoke "$normal_home" "$normal_cwd" "two words" ""
status=0
quest-termux sh -c 'exit 23' || status=$?
test "$status" -eq 23
test "$HOME" = "$normal_home"
git --version
echo "PASS: shared HOME/cwd, arguments, nested entry, exit status, CLI tools, HTTPS, package database, app bridge"
