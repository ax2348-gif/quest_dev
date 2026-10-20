package dev.quest.runtime;

import android.content.*;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;

/** Start the provisioned runtime after user storage unlocks or an APK update. */
public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        String result;
        boolean enabled = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .getBoolean("start_on_boot", true);
        File root = context.getFilesDir();
        File keys = new File(root, "home/.ssh/authorized_keys");
        if (!enabled) {
            result = "Skipped: automatic startup disabled";
        } else if (!keys.isFile() || keys.length() == 0 || !new File(root, "tailnet_ip").isFile()) {
            result = "Skipped: provision SSH keys and Tailscale address first";
        } else {
            try {
                context.startForegroundService(new Intent(context, RuntimeService.class));
                result = "Requested foreground SSH service";
            } catch (RuntimeException e) {
                result = "Failed: " + e;
                Log.e("QuestRuntimeBoot", "Automatic startup failed", e);
            }
        }
        String event = new Date() + "\n" + action + "\nUptime ms: "
            + SystemClock.elapsedRealtime() + "\n" + result + "\n";
        Log.i("QuestRuntimeBoot", event);
        try {
            Files.write(new File(root, "autostart.txt").toPath(), event.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("QuestRuntimeBoot", "Could not record startup event", e);
        }
    }
}
