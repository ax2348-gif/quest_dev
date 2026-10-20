#!/usr/bin/env python3
"""One-time USB provisioning. No Tailscale credentials are read or embedded."""
import argparse
import ipaddress
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--authorized-keys", type=pathlib.Path, required=True)
p.add_argument("--tailnet-ip", required=True)
args = p.parse_args()
assert ipaddress.ip_address(args.tailnet_ip) in ipaddress.ip_network("100.64.0.0/10")
keys = args.authorized_keys.read_bytes()
assert keys.strip() and b"PRIVATE KEY" not in keys, "Provide public keys only"
adb = ["adb", "-s", args.serial]

def run(*parts, data=None):
    return subprocess.run(adb + list(parts), input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True, timeout=90).stdout

print(run("install", "-r", str(ROOT / "artifacts/quest-dev-runtime.apk")).decode().strip())
run("shell", "am", "force-stop", "dev.quest.runtime")
for name, data in (("authorized_keys", keys), ("tailnet_ip", args.tailnet_ip.encode() + b"\n")):
    run("shell", "content", "write", "--uri",
        f"content://dev.quest.runtime.files/{name}", data=data)
print(run("shell", "content", "call", "--uri", "content://dev.quest.runtime.files",
          "--method", "start").decode().strip())
for _ in range(15):
    time.sleep(1)
    status = run("shell", "content", "read", "--uri",
                 "content://dev.quest.runtime.files/status").decode()
    if status.startswith("SSH running:"):
        print(status.strip())
        print(run("shell", "content", "read", "--uri",
                  "content://dev.quest.runtime.files/host_public_key").decode().strip())
        break
else:
    raise RuntimeError("SSH did not start: " + status)
