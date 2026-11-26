# Setting up a headset

**The easy way:** plug the headset in and run robot_utils' setup wizard, `python3 tools/quest_teleop/quest_setup.py`.
It installs Tailscale and this runtime (the pinned APK of tag `collab-v4`, 1.7.0, SHA-256 checked), authorises an SSH
key, sets the runtime's Tailscale address, adds `Host quest` to `~/.ssh/config` and installs quest_env. It is safe
to rerun after a failure. The checklist and troubleshooting are in robot_utils' guide:
[docs/quest_setup.md](https://github.com/ax2348-gif/robot_utils/blob/collab/docs/quest_setup.md).

The rest of this page is the same by hand, for when the wizard cannot be used. [README.txt](README.txt) is the full
reference; its addresses, USB serial and key fingerprints belong to the maintainer's headset.

## You need

- The headset in developer mode, with "Allow USB debugging" accepted inside it. `adb devices` shows its serial.
- Tailscale on the headset and on your computer, in the same tailnet. The Android APK is at
  <https://pkgs.tailscale.com/stable/#android>; install it with `adb install`, then sign in inside the headset.
  Note the headset's `100.x.y.z` address.
- An SSH key pair. Only the public key goes to the headset.
- The APK: `artifacts/quest-dev-runtime.apk` on branch `collab` / tag `collab-v4`, or build it (AGENTS.md,
  "Building").

## Install and provision

```bash
python3 install.py --serial <adb serial> --authorized-keys ~/.ssh/<your key>.pub --tailnet-ip <headset 100.x.y.z>
```

It installs or updates the APK, writes the keys (replacing the set) and the address, starts the SSH service, and
prints the headset's host key. Then in `~/.ssh/config`:

```
Host quest
    HostName <headset 100.x.y.z>
    Port 8022
    User quest
    IdentityFile ~/.ssh/<your key>
```

`ssh quest` opens a shell in `~/workspace`.

**Without Tailscale** (a fallback over the cable or wireless debugging): the runtime also listens on its loopback.
Run `adb forward tcp:8022 tcp:8022`, then use `HostName 127.0.0.1` in the entry. With `--tailnet-ip 127.0.0.1` it
listens on loopback only.

## Next

Install quest_env: [quest_env README, "Setting up a headset"](https://github.com/ax2348-gif/quest_env/blob/collab/README.md#setting-up-a-headset).
That needs Git on the headset (DEVTOOLS.txt); the wizard copies quest_env without it.
