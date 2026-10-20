#!/system/bin/sh
# Exercises actual binaries, HTTPS verification, and Git update semantics.
set -eu
git --version
curl --version | head -2
wget --version | head -2
test_dir="$(mktemp -d "$HOME/tmp/devtools-smoke.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
curl --fail --silent --show-error --max-time 30 https://github.com/ -o "$test_dir/curl.html"
test -s "$test_dir/curl.html"
wget -q --timeout=30 --tries=1 https://github.com/ -O "$test_dir/wget.html"
test -s "$test_dir/wget.html"
if curl --silent --max-time 15 --cacert /dev/null https://github.com/ -o /dev/null; then
    echo "FAIL: curl accepted an empty CA bundle" >&2
    exit 1
fi
git -c http.sslVerify=true clone -q --depth=1 https://github.com/octocat/Hello-World.git "$test_dir/public"
test -s "$test_dir/public/README"
git -C "$test_dir/public" -c http.sslVerify=true pull --ff-only

git init -q "$test_dir/origin"
printf 'one\n' > "$test_dir/origin/version.txt"
git -C "$test_dir/origin" add version.txt
git -C "$test_dir/origin" -c user.name=RuntimeTest -c user.email=runtime-test@localhost commit -qm one
git clone -q --no-hardlinks "$test_dir/origin" "$test_dir/replica"
printf 'two\n' > "$test_dir/origin/version.txt"
git -C "$test_dir/origin" -c user.name=RuntimeTest -c user.email=runtime-test@localhost commit -qam two
git -C "$test_dir/replica" pull --ff-only
test "$(cat "$test_dir/replica/version.txt")" = two
test "$(git -C "$test_dir/replica" rev-parse HEAD)" = "$(git -C "$test_dir/origin" rev-parse HEAD)"
echo "PASS: HTTPS downloads, certificate rejection, Git HTTPS clone/pull, local commit/clone/update"
