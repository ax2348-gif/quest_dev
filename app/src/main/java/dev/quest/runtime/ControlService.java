package dev.quest.runtime;

import android.app.Service;
import android.content.Intent;
import java.io.File;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Hosts the local launch bridge (quest-launch, quest-app, quest-native) in the main process,
 * where the BlackBox container is initialized. RuntimeService runs SSH in the separate ":ssh"
 * process and binds here, so this process stays alive while SSH runs and Android recreates it
 * if it dies. A crash here no longer takes the SSH listener down with it.
 *
 * In the other direction, losing that binding without an intentional stop means the ":ssh"
 * process died, so this asks for SSH again right away. If Android already scheduled its own
 * restart, the request waits for that; the watchdog alarm in RuntimeService is the backstop.
 */
public class ControlService extends Service {
    private final IBinder binder = new Binder();
    private AppControl control;

    public void onCreate() {
        super.onCreate();
        try { RuntimeEnvironment.prepare(this); control = new AppControl(this); }
        catch (Exception e) { Log.e("QuestRuntimeControl", "Launch bridge unavailable", e); }
    }
    public IBinder onBind(Intent intent) { return binder; }
    public boolean onUnbind(Intent intent) {
        if (!new File(getFilesDir(), RuntimeService.STOPPED_FLAG).exists()) {
            Log.w("QuestRuntimeControl", "SSH process ended unexpectedly; requesting restart");
            try { startForegroundService(new Intent(this, RuntimeService.class)); }
            catch (RuntimeException e) { Log.e("QuestRuntimeControl", "SSH restart request failed", e); }
        }
        return false;
    }
    public void onDestroy() {
        if (control != null) control.close();
        super.onDestroy();
    }
}
