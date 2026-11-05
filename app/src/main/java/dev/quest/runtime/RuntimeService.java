package dev.quest.runtime;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.lang.Process;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * One key-only SSH listener bound to the configured Tailscale IPv4 address.
 * Runs in its own ":ssh" process; the launch bridge lives in ControlService (main process).
 */
public class RuntimeService extends Service {
    /** Ticks (2 s each) the address may be missing before the listener is stopped. Tailscale
     *  rebuilds its VPN interface on reconfiguration; a bound listener survives that gap. */
    private static final int ADDRESS_GRACE_TICKS = 5;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile Process server;
    private PowerManager.WakeLock wakeLock;
    private File root, home, logFile;
    private String boundIp = "";
    private volatile boolean stopped;
    private String lastStatus = "";
    private int addressMissing;
    private final ServiceConnection controlConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {}
        public void onServiceDisconnected(ComponentName name) {
            android.util.Log.w("QuestRuntime", "Main process died; Android will recreate the launch bridge");
            logPreviousExits();   // record why, while SSH keeps running
        }
    };

    public void onCreate() {
        super.onCreate();
        root = getFilesDir(); home = new File(root, "home"); logFile = new File(root, "runtime.log");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("runtime", "Developer runtime",
            NotificationManager.IMPORTANCE_LOW));
        PendingIntent show = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, RuntimeService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(8022, new Notification.Builder(this, "runtime")
            .setContentTitle("Quest developer runtime")
            .setContentText("SSH on Tailscale • port 8022")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(show)
            .addAction(new Notification.Action.Builder(null, "Stop listener", stop).build())
            .setOngoing(true).build());
        logPreviousExits();
        // Keeps the main process (launch bridge) alive and recreated while SSH runs.
        bindService(new Intent(this, ControlService.class), controlConnection, BIND_AUTO_CREATE);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "QuestRuntime:SSH");
        wakeLock.acquire();
        worker.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
    }
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }
    public IBinder onBind(Intent i) { return null; }

    private void status(String text) throws IOException {
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        Files.write(new File(root, "status.txt").toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    }
    private ProcessBuilder command(String... args) {
        ProcessBuilder p = new ProcessBuilder(args).directory(home).redirectErrorStream(true);
        p.environment().put("QUEST_RUNTIME_HOME", home.getAbsolutePath());
        p.environment().put("HOME", home.getAbsolutePath());
        p.environment().put("TMPDIR", new File(home, "tmp").getAbsolutePath());
        return p;
    }
    private void initialize() throws Exception {
        RuntimeEnvironment.prepare(this);
        File hostKey = new File(root, "ssh_host_ed25519_key");
        if (!hostKey.exists()) {
            Process keygen = command(new File(home, "bin/dropbearkey").toString(),
                "-t", "ed25519", "-f", hostKey.toString())
                .redirectOutput(new File(root, "host_public_key.txt")).start();
            if (!keygen.waitFor(15, TimeUnit.SECONDS)) {
                keygen.destroyForcibly(); throw new IOException("Host key generation timed out");
            }
            if (keygen.exitValue() != 0) throw new IOException("Host key generation failed");
        }
        Os.chmod(hostKey.getAbsolutePath(), 0600);
    }
    private String configuredIp() throws Exception {
        String ip = read(new File(root, "tailnet_ip"));
        if (!ip.matches("100\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))
            throw new IOException("Expected a Tailscale IPv4 address");
        String[] octets = ip.split("\\.");
        int second = Integer.parseInt(octets[1]);
        if (second < 64 || second > 127 ||
            Integer.parseInt(octets[2]) > 255 || Integer.parseInt(octets[3]) > 255)
            throw new IOException("Address outside Tailscale range");
        NetworkInterface nic = NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
        return nic != null && nic.isUp() ? ip : "";
    }
    private synchronized void stopListener() {
        if (server != null) { server.destroy(); server = null; }
        boundIp = ""; addressMissing = 0;
    }
    /** Append why this app's processes last died (Android 11+), once per event, to runtime.log. */
    private void logPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            File seen = new File(root, "exit_reasons_seen");
            long since = seen.exists() ? Long.parseLong(read(seen)) : 0, newest = since;
            List<ApplicationExitInfo> exits = getSystemService(ActivityManager.class)
                .getHistoricalProcessExitReasons(null, 0, 16);
            StringBuilder out = new StringBuilder();
            for (int i = exits.size() - 1; i >= 0; i--) {   // oldest first
                ApplicationExitInfo e = exits.get(i);
                if (e.getTimestamp() <= since) continue;
                newest = Math.max(newest, e.getTimestamp());
                out.append(String.format(Locale.ROOT,
                    "[exit] %tF %<tT %s pid=%d reason=%s status=%d importance=%d pss=%dkB rss=%dkB %s%n",
                    new Date(e.getTimestamp()), e.getProcessName(), e.getPid(),
                    exitReason(e.getReason()), e.getStatus(), e.getImportance(),
                    e.getPss(), e.getRss(), e.getDescription() == null ? "" : e.getDescription()));
            }
            if (out.length() > 0)
                Files.write(logFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            Files.write(seen.toPath(), Long.toString(newest).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("QuestRuntime", "Could not record previous exit reasons", e);
        }
    }
    private static String exitReason(int reason) {
        String[] names = {"UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE",
            "ANR", "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE",
            "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER",
            "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"};
        return reason >= 0 && reason < names.length ? names[reason] : Integer.toString(reason);
    }
    private void tick() {
        if (stopped) return;
        try {
            File keys = new File(home, ".ssh/authorized_keys");
            if (!keys.exists() || keys.length() == 0) {
                stopListener(); status("Provision authorized_keys before starting SSH."); return;
            }
            Os.chmod(keys.getAbsolutePath(), 0600);
            String ip = configuredIp();
            if (ip.isEmpty()) {
                if (server != null && server.isAlive() && ++addressMissing < ADDRESS_GRACE_TICKS) {
                    status("SSH running: Tailscale address briefly missing, keeping the listener.");
                    return;
                }
                stopListener(); status("Waiting for the configured Tailscale address."); return;
            }
            addressMissing = 0;
            if (server != null && server.isAlive() && boundIp.equals(ip)) return;
            stopListener(); initialize();
            if (logFile.length() > 1024 * 1024) Files.delete(logFile.toPath());
            Process child = command(new File(home, "bin/dropbear").toString(),
                "-F", "-s", "-j", "-k", "-p", ip + ":8022",
                "-r", new File(root, "ssh_host_ed25519_key").toString(),
                "-P", new File(root, "dropbear.pid").toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start();
            synchronized (this) {
                if (stopped) { child.destroy(); return; }
                server = child; boundIp = ip;
            }
            if (child.waitFor(250, TimeUnit.MILLISECONDS)) {
                stopListener(); status("SSH exited. Inspect runtime.log for details."); return;
            }
            status("SSH running: quest@" + ip + ":8022\n"
                + "Workspace: " + new File(home, "workspace") + "\n"
                + "UID: " + android.os.Process.myUid() + "\n"
                + "Telemetry example: 127.0.0.1:9391 (when selected and running)");
        } catch (Exception e) {
            stopListener();
            try { status("SSH paused: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }
    public void onDestroy() {
        stopped = true; worker.shutdownNow(); stopListener();
        unbindService(controlConnection);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { status("SSH listener stopped. Existing authenticated sessions may remain open."); }
        catch (IOException ignored) {}
        super.onDestroy();
    }
}
