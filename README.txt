TERMINAL UPDATE: version 1.5.1 is installed. Open new terminal in the runtime
window launches a separate translucent terminal. Multiple independent terminals
are supported. See TERMINALS.txt for controls, session lifetime and validation.
Git/curl/Wget are installed separately in the persistent runtime home and work
from both local terminals and SSH. See DEVTOOLS.txt for usage and updates.
Native swapping previously passed on version 1.4.1; see NATIVE-APPS.txt.

QUEST DEVELOPER RUNTIME 1.5.1 — experimental private SDK 28 app
========================================================

CONNECT FROM A MACHINE IN YOUR TAILNET

    ssh -p 8022 quest@100.72.77.83

If SSH does not automatically select your key:

    ssh -p 8022 -i /path/to/your/private-key quest@100.72.77.83

The private key stays on your computer. Both supplied public keys are authorized:
  ED25519 SHA256:hPe2GOl7DHj4MNwive61IM7I8k83yew52B6pI8CU4U4
  ECDSA   SHA256:BeuBf5ZoIv6tqTTb99Pep1lyjFRSIw8OqviDrFebQTE

Verify this Quest SSH HOST fingerprint on first connection:
  SHA256:FzTSs7qsiRkiM92iocFFtwC1NiZz3mBnNPRGSo81XrU

That host key is generated on the Quest and persists across APK updates.
Uninstalling/clearing app data erases the host key and workspace.

TRANSFER FILES

    rsync -rtp -e 'ssh -p 8022' ./project/ quest@100.72.77.83:workspace/project/
    rsync -rtp -e 'ssh -p 8022' quest@100.72.77.83:workspace/project/ ./download/

This initial bundle implements rsync over SSH, not SFTP. Modern scp defaults to
SFTP and is not supported. For a single file you can also use:

    ssh -p 8022 quest@100.72.77.83 'cat > workspace/example.txt' < example.txt

RUN AN APK INSIDE THE RUNTIME (version 1.3)

On your tailnet computer, upload a trusted APK:

    rsync -rt -e 'ssh -p 8022' ./my-app.apk quest@100.72.77.83:workspace/my-app.apk
    ssh -p 8022 quest@100.72.77.83

In the Quest SSH shell:

    quest-app install "$HOME/workspace/my-app.apk"
    quest-app list
    quest-app launch your.app.package

The install response gives the package name. This imports the APK into the
runtime's private registry/storage; it does not use Android PackageInstaller,
require ADB, or show an Android install-confirmation dialog. Launch opens the
guest's Android UI in a Quest panel. Headset state/background-launch rules
can still affect display. A launch response only means the request was made.

The tested demo is already uploaded and imported:

    quest-app launch dev.quest.guestdemo
    quest-app stop dev.quest.guestdemo

Other commands:

    quest-app list
    quest-app remove your.app.package

remove deletes that guest and its private data. stop ends that guest's
processes, leaving the SSH service running. Re-importing an APK with the same
package name is the update path; back up valuable guest data before experiments.
The existing quest-launch command still launches OS-installed apps by component.

BOUNDARY AND COMPATIBILITY

BlackBox loads an ordinary APK's classes/resources and substitutes Android
framework services so its components run in host-owned processes. It is not
a second Android OS or a security boundary between guests. Guest-reported UIDs
can be virtual values; the kernel UID remains the runtime's UID 10156 here.
Only load trusted APKs: a guest can potentially access runtime files and network
capabilities. It does not acquire root or Android shell privileges.

Confirmed: the permission-free Android Activity demo (built independently of
BlackBox APIs) renders and interacts on this Quest. The user confirmed the first
isolated-host UI test. In the integrated runtime, SSH upload/import/launch works,
and Android logs record the guest Activity created/resumed from runtime-private
storage while Android's installed-package list contains no guest package.

Not established: arbitrary third-party APK compatibility, Play services/DRM,
split APK sets, 32-bit libraries, protected platform services, or guest OpenXR
apps. This arm64 prototype does not add camera/microphone/location/storage
permissions for guests. Apps needing them will need further host integration.
The separate bundled native XR test is unchanged and not retested by this work.

The temporary "Quest APK container probe" host remains installed but stopped.
The usable workflow above runs in "Quest developer runtime" itself.

WHAT IS INSIDE

  * Dropbear 2026.94 SSH server, public-key authentication only.
  * rsync 3.5.1.
  * Git 2.56.0, curl 8.23.0 and Wget 1.25.0 (persistent developer-tools bundle).
  * Android's /system/bin/sh and existing system utilities.
  * Persistent home: /data/user/0/dev.quest.runtime/files/home
  * Persistent project directory: $HOME/workspace
  * Executable search path: $HOME/bin:/system/bin
  * A bundled native OpenXR/passthrough test in a separate :xr process.

Interactive login starts in $HOME/workspace. Noninteractive SSH commands start
in $HOME, so use workspace/... paths in rsync and remote commands.

This is an Android/Bionic arm64 runtime. Uploaded executables must be built for
Android arm64 (e.g. with the NDK), rather than ordinary glibc Linux binaries.
It currently does not include Python, a compiler, a package manager or a coding
agent. Those can be added as Android-compatible binaries.

The runtime remains inside one Android app UID. It cannot read other apps'
private files, become root, or replace ADB's privileged device management.
Its XR activity shares that UID and workspace, with an independent process.
Version 1.3 packages the XR code in the APK. Version 1.4.1 adds replaceable native
libraries with an XR-process restart; see NATIVE-APPS.txt for validation details.

START / STOP

Once started, leaving the app's window does not intentionally stop SSH.
The foreground service holds a partial CPU wake lock while running.
This costs battery; it does not turn the display on or grant camera access.

Open "Quest developer runtime" in Unknown Sources to start it and view status.
The app also has a Stop SSH listener button. Existing authenticated sessions
can remain open after stopping the listener. A full app force-stop ends all
runtime processes.

USB bootstrap/start, without opening a headset window:

    adb -s 340YC10GBF06GV shell content call \
      --uri content://dev.quest.runtime.files --method start

USB stop listener:

    adb -s 340YC10GBF06GV shell content call \
      --uri content://dev.quest.runtime.files --method stop

USB stop all app processes:

    adb -s 340YC10GBF06GV shell am force-stop dev.quest.runtime

Status:

    adb -s 340YC10GBF06GV shell content read \
      --uri content://dev.quest.runtime.files/status

Logs:

    adb -s 340YC10GBF06GV shell content read \
      --uri content://dev.quest.runtime.files/log

AUTOMATIC STARTUP (enabled by default in version 1.1)

After Android finishes booting and unlocks user storage, the app's boot receiver
starts the foreground SSH service. It also starts after an in-place APK update.
The service waits for the configured Tailscale IP before binding port 8022.
It does not open a headset window or start XR.

To disable this, open the runtime and uncheck "Start SSH automatically after
reboot and app updates". The Stop SSH listener button stops the current listener;
it does not disable startup on the next reboot/update. Android force-stop can
suppress automatic startup until the app is explicitly launched again.

The boot receiver needs only the normal RECEIVE_BOOT_COMPLETED permission.
It does not require root, accessibility or secure-settings permissions.
Keys and workspace stay in credential-protected app storage. If a system PIN
is configured, first unlock after reboot may be needed before SSH starts.
This is separate from Quest's physical-button camera/tracking privacy lock.

Read the latest automatic-start event:

    adb -s 340YC10GBF06GV shell content read \
      --uri content://dev.quest.runtime.files/autostart

Android may still kill processes. Long-duration sleep/power behavior remains
an experiment to validate.

PROCESS LAYOUT: the SSH service and dropbear run in their own
":ssh" process. The main process holds the BlackBox container, terminals and
the launch bridge (ControlService, bound by the SSH service so Android keeps
and recreates it); ":xr" holds the native XR activity. A crash in the main
process no longer stops SSH. A force-stop still ends every process.
The listener survives up to 10 s of the Tailscale address disappearing (the
Tailscale app rebuilds its VPN interface on reconfiguration) before stopping.
Each time the SSH service starts, and whenever the main process dies, Android's
recorded exit reasons for this app (LOW_MEMORY, CRASH_NATIVE, USER_REQUESTED
for force-stop, ...) are appended to runtime.log as "[exit]" lines. The normal SSH connection does not use USB once
Tailscale and this service run.

Android boot/storage reference:
https://developer.android.com/privacy-and-security/direct-boot

NATIVE XR TEST

Use the app's "Open native XR test" button, or from the remote shell try:

    quest-launch dev.quest.runtime/android.app.NativeActivity

The Quest must permit immersive activity launch and camera/tracking access.
The physical-power-button privacy lock can block that even while SSH works.
The native test publishes controller/headset data on localhost:9391:

    nc 127.0.0.1 9391

SSH can carry that stdout stream back to your computer:

    ssh -p 8022 quest@100.72.77.83 'nc 127.0.0.1 9391'

The test supports one telemetry client. SSH TCP/agent/X11 forwarding is disabled
in the server build. An authenticated shell can still run network programs.

NETWORK AND AUTHENTICATION

The listener binds specifically to 100.72.77.83:8022, never 0.0.0.0 or the
Quest's Wi-Fi address. The service checks every two seconds and stops the
listener if the configured address disappears. It restarts when the address
returns. It does not create a firewall or modify Tailscale access policy.
Tailnet access rules must allow your client to reach this device and port.

This uses ordinary SSH through the existing Tailscale Android VPN app.
It is not Tailscale SSH and needs no Tailscale auth key in the runtime.
The file supplied as "tailscale_key" was another SSH public key and was added
to authorized_keys accordingly.

All logins map to the runtime's own UID; the only SSH username is "quest".
Password authentication is compiled out. Public-key signature checks and
authorized_keys ownership/permission checks remain in upstream Dropbear.
The bootstrap provider requires Android's privileged DUMP permission, and
only exposes fixed provisioning/status paths. It never exposes the private
host key. The APK is not debuggable.

BUILD / REPROVISION

From this directory:

    bash build_native.sh
    bash build.sh --offline
    python3 install.py --serial 340YC10GBF06GV \
      --authorized-keys /path/to/authorized_keys --tailnet-ip 100.72.77.83

install.py replaces the authorized_keys file with the supplied public keys.
It updates the APK without deleting app data and starts the foreground service.

The local build defaults use the toolchain under /tmp/teleop_tools. Override
JAVA_HOME, ANDROID_HOME, ANDROID_USER_HOME and GRADLE_USER_HOME as needed.
NDK 27.2.12479018, SDK 35, CMake 3.22.1, Gradle 8.7 and Java 17 were used.
The APK targets SDK 28 intentionally for private-workspace native execution.
The release is signed with the local development key but debuggable=false.
Keep that signing key if you need future in-place updates.

Native upstream source archives are included in vendor/, with fixed SHA-256
checks in build_native.sh. Local Android adaptations are in patch_dropbear.py:
single app-UID login mapping, Android shell selection, avoiding redundant
UID/GID-changing syscalls, Android UID lookup for PTY/login bookkeeping, and
verification of Android-managed private PTY permissions without chmod/chown.
No upstream signature-verification code is replaced.

Dropbear source: https://dropbear.nl/mirror/releases/dropbear-2026.94.tar.bz2
rsync source: https://rsync.samba.org/ftp/rsync/src/rsync-3.5.1.tar.gz
See licenses/ and upstream archives for licensing and bundled dependencies.
The OpenXR test derives from the existing local Quest telemetry experiment.

VALIDATION

test_usb.py performs real SSH authentication through a bidirectional ADB byte
proxy to the Quest's own Tailscale address. This isolates the runtime from the
currently unavailable erid-to-Quest tailnet route. It checks a shell, exit
status, an interactive PTY, rsync roundtrip, uploaded native execution, and
rejection of missing/untrusted keys and the root username.

USB-proxied SSH is not proof of connectivity from another tailnet machine.
The final external check is the ssh command at the top of this document.

Observed on this Quest (2026-10-20):
  PASS: interactive and command SSH as app UID 10156.
  PASS: exit-status propagation, rsync roundtrip and uploaded native execution.
  PASS: missing key, untrusted key and root-login rejection.
  PASS: listener stop/start, workspace persistence and stable host key.
  PASS: connection to the Quest Wi-Fi address on port 8022 is refused.
  PASS: temporary test key removed; installed keys match the two supplied keys.
  PASS (user confirmed): real SSH connection from another tailnet computer.
  NOT RETESTED in this APK: focused XR operation and prolonged sleep behavior.

The earlier SDK 28 execution probe demonstrated focused OpenXR frames. This
bundle includes that native code in a separate process, but that previous
result is not claimed as an XR validation of this APK.

This app changes only its own SSH listener. The previous ADB TCP configuration
is separate; binding SSH to a Tailscale address does not secure ADB's listener.

Version 1.1 automatic-start verification:
  PASS: stopped listener before APK update; Android MY_PACKAGE_REPLACED event
        started the foreground service without manually opening or starting it.
  PASS: host key and both authorized public keys preserved across the update.
  PASS: listener running on 100.72.77.83:8022 after automatic startup.
  PASS: actual reboot; BOOT_COMPLETED started SSH at about 16 seconds uptime,
        with no manual app launch or service-start command.

ERID ACCESS (explicitly authorized after initial testing)

The erid test key is now retained as a third authorized key:
  ED25519 SHA256:rh/uNZgKlKNHX4v5UoqPCP13Hqrwt+Ogq3hTShxU92c
Both user-supplied public keys remain authorized. Authentication with erid's
key was verified through the USB byte proxy after the successful reboot.
This supersedes the earlier temporary-key removal as the current access state.

To route erid through your other computer's tailnet connection, run there:

    ssh -NT -o ExitOnForwardFailure=yes \
      -R 127.0.0.1:2222:100.72.77.83:8022 adrianna@erid-2

The listener on erid is loopback-only. It forwards directly to Quest SSH.
Quest host-key verification and SSH public-key authentication still apply.

LAUNCHING AN INSTALLED ANDROID UI FROM SSH (version 1.2)

    quest-launch --check dev.quest.uidemo/.MainActivity
    quest-launch dev.quest.uidemo/.MainActivity

The first command only resolves the installed activity; it opens no window.
The second requests its launch through the runtime Android service.
A "launch requested" response is not confirmation that the headset displayed
the window; Android background-launch policy or headset state may intervene.

The stock /system/bin/am command cannot be used for this from our SSH UID on
this Quest: it claims the com.android.shell package and Android rejects the
UID mismatch. quest-launch uses an app-local Unix socket, checks the connecting
process UID, and calls startActivity from the runtime's own Android context.
It adds no TCP listener or privileged Android permissions.

An APK is an archive, not an executable. chmod +x does not make one executable;
the shell may interpret its ZIP data as a script and report gibberish syntax
errors. quest-launch addresses Android-installed packages; quest-app imports
APKs into the runtime container instead (see the workflow at the top).
Quest UI demo is installed separately, with its own Android UID. This launch
bridge does not implement loading arbitrary XR libraries inside our app.

Verified in version 1.2: automatic restart after update, SSH authentication,
and resolving the UI demo with --check. The user subsequently confirmed the
separately installed UI demo launched successfully.

Version 1.3 verification: SSH authentication, upload, private import/list,
guest Activity create/resume, stop/relaunch and original installed-app lookup
passed. Guest stayed absent from Android package registry. SSH and guest registry
remained available after stopping the guest. See results/apk-container.json.
