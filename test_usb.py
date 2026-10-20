#!/usr/bin/env python3
"""Test real SSH authentication through an ADB byte proxy to the tailnet-bound socket."""
import argparse
import hashlib
import json
import pathlib
import shlex
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--identity", type=pathlib.Path, required=True)
p.add_argument("--tailnet-ip", required=True)
p.add_argument("--work", type=pathlib.Path, required=True)
args = p.parse_args()
args.work.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.serial]

def run(cmd, **kwargs):
    try:
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=30, **kwargs)
    except subprocess.CalledProcessError as e:
        print(e.stderr.decode(errors="replace"), flush=True)
        raise

raw = run(adb + ["shell", "content", "read", "--uri",
                "content://dev.quest.runtime.files/host_public_key"], check=True).stdout.decode()
public = next(line for line in raw.splitlines() if line.startswith("ssh-ed25519 "))
known = args.work / "known_hosts"
known.write_text(f"[{args.tailnet_ip}]:8022 {' '.join(public.split()[:2])}\n")
proxy = shlex.join(adb + ["shell", "-T", "nc", args.tailnet_ip, "8022"])
base = ["ssh", "-F", "/dev/null", "-p", "8022", "-i", str(args.identity.resolve()),
        "-o", "IdentityAgent=none", "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=8", "-o", f"UserKnownHostsFile={known.resolve()}",
        "-o", "StrictHostKeyChecking=yes", "-o", f"ProxyCommand={proxy}"]
destination = f"quest@{args.tailnet_ip}"
results = {}

def ssh(command, *, check=True, extra=None):
    return run(base + (extra or []) + [destination, command], check=check)

shell = ssh("id; pwd; echo HOME=$HOME; echo PATH=$PATH; rsync --version")
results["shell"] = shell.stdout.decode()
print(results["shell"], flush=True)
assert "uid=2000(" not in results["shell"] and "uid=0(" not in results["shell"]
assert "/data/user/0/dev.quest.runtime/files/home" in results["shell"]
results["exit_code"] = ssh("exit 7", check=False).returncode
assert results["exit_code"] == 7

# Force the PTY path used by an interactive login.
with tempfile.TemporaryFile() as out:
    # Keep stdin open like a real terminal; EOF can tear down an allocated PTY
    # before the remote child starts.
    child = subprocess.Popen(base + ["-tt", destination, "test -t 0 && echo PTY_OK"],
                             stdin=subprocess.PIPE, stdout=out, stderr=out)
    try:
        assert child.wait(timeout=15) == 0
        out.seek(0)
        assert b"PTY_OK" in out.read()
    finally:
        child.stdin.close()
        if child.poll() is None:
            child.kill()
            child.wait()
results["interactive_pty"] = True

payload = args.work / "roundtrip.txt"
payload.write_bytes(b"Quest runtime rsync round trip\n" * 1000)
transport = shlex.join(base)
run(["rsync", "-rt", "-e", transport, str(payload),
     destination + ":workspace/roundtrip.txt"], check=True)
returned = args.work / "returned.txt"
run(["rsync", "-rt", "-e", transport, destination + ":workspace/roundtrip.txt",
     str(returned)], check=True)
assert payload.read_bytes() == returned.read_bytes()
results["rsync_sha256"] = hashlib.sha256(returned.read_bytes()).hexdigest()

probe = ROOT.parent / "quest-execution-probe/artifacts/payload/probe"
run(["rsync", "-rtp", "-e", transport, str(probe),
     destination + ":workspace/probe"], check=True)
native = ssh("chmod 700 workspace/probe; workspace/probe")
results["uploaded_native"] = native.stdout.decode()

denied = run(base + ["-o", "PubkeyAuthentication=no", destination, "id"])
assert denied.returncode == 255 and b"Permission denied" in denied.stderr
results["no_key_denied"] = True
wrong_user = run(base + [f"root@{args.tailnet_ip}", "id"])
assert wrong_user.returncode == 255 and b"Permission denied" in wrong_user.stderr
results["root_login_denied"] = True

# A real but untrusted key must also fail.
bad = args.work / "untrusted_key"
if not bad.exists():
    run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(bad)], check=True)
wrong = base.copy()
wrong[wrong.index("-i") + 1] = str(bad.resolve())
rejected = run(wrong + [destination, "id"])
assert rejected.returncode == 255 and b"Permission denied" in rejected.stderr
results["untrusted_key_denied"] = True
results["transport"] = "ADB shell -T nc to Quest's own Tailscale IP; not a cross-tailnet test"
(args.work / "results.json").write_text(json.dumps(results, indent=2) + "\n")
print(json.dumps(results, indent=2), flush=True)
