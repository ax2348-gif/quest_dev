# AGENTS.md: context for coding agents

quest_dev ("Quest developer runtime", package `dev.quest.runtime`) is an Android app for Meta Quest. It provides:
- a Dropbear SSH server (port 8022, public keys only, user `quest`) and rsync;
- terminals and a persistent home with `~/workspace`;
- a native XR host process whose library [quest_env](https://github.com/ax2348-gif/quest_env) replaces.

It is the base for the teleop stack in [robot_utils](https://github.com/ax2348-gif/robot_utils); start with that
repository's AGENTS.md for the whole picture. README.txt is the detailed reference, written for the maintainer's
own headset: its addresses and serials are not yours.

## How it is installed and configured

- **Normally:** by robot_utils' setup wizard (`tools/quest_teleop/quest_setup.py`) over USB. It installs the
  pinned APK (`artifacts/quest-dev-runtime.apk` at tag `collab-v1`, SHA-256 checked), then configures the runtime
  through its content provider.
- **By hand:** `install.py --serial S --authorized-keys FILE --tailnet-ip ADDR` does the same (SETUP.md).
- **Provider** `content://dev.quest.runtime.files` (`adb shell content ...`; adb's shell identity only):
  - write `/authorized_keys` (replaces the whole set) and `/tailnet_ip`;
  - read `/status`, `/log`, `/autostart`, `/host_public_key`;
  - `call --method start|stop`.
- **Listening** (1.6.0, branch `collab`): always on `127.0.0.1:8022`, for `adb forward tcp:8022 tcp:8022`; plus
  the configured Tailscale address while that address is up. `tailnet_ip` set to `127.0.0.1` means loopback only.
- **Uninstalling or clearing data erases** the home, the workspace, the authorised keys and the SSH host key.
  An APK signed by a different build cannot be updated in place: the wizard asks before uninstalling.
- **Starting:** automatic after boot and after updates, once keys and an address are configured. A force-stop
  disables that until the app is opened from Library → Unknown sources, or `call --method start`.

## Building

- **Host:** Linux x86-64, or macOS (the scripts pick the NDK's `darwin-x86_64` prebuilt).
- **Toolchain:** Java 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.
- **Steps:**
  ```bash
  export JAVA_HOME=... ANDROID_HOME=... ANDROID_USER_HOME=~/.android GRADLE_USER_HOME=~/.gradle
  bash build_native.sh && bash build.sh      # -> artifacts/quest-dev-runtime.apk
  ```
- **On macOS, build in a case-sensitive volume.** The bundled BlackBox source (`Bcore/`) has two nested classes
  that differ only in case (`IPackageManagerProxy$GetComponentEnabledSetting` / `$getComponentEnabledSetting`). On
  the default case-insensitive APFS one class file overwrites the other and D8 fails: "requires its nest mates ...
  (unavailable)". Use a case-sensitive sparse image (no sudo):
  ```bash
  hdiutil create -size 20g -type SPARSE -fs "Case-sensitive APFS" -volname questbuild ~/quest_build_cs.sparseimage
  hdiutil attach -nobrowse ~/quest_build_cs.sparseimage
  rsync -a --exclude build/ ./ /Volumes/questbuild/quest-dev-runtime/
  ```
  Then build there and copy `artifacts/` back.
- **Signing:** with the building machine's debug key (`$ANDROID_USER_HOME/debug.keystore`). Keep the same machine
  and key so installs can update in place.
- **Releasing for collaborators:**
  1. Force-add the APK on branch `collab` (`artifacts/` is ignored), with a version bump in `app/build.gradle`.
  2. Tag it.
  3. Update `RUNTIME_VERSION`, `RUNTIME_URL` and `RUNTIME_SHA256` in robot_utils' `quest_setup.py`.

## Conventions

- **`main` is the maintainer's line.** Collaborator changes go on `collab`.
- **Only public keys ever go to the headset.** Don't weaken key-only login or bind to a Wi-Fi address.
