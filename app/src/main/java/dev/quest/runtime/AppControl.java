package dev.quest.runtime;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONArray;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** App-UID-only local launch bridge for programs started by SSH. No TCP port. */
final class AppControl implements Closeable {
    private final Context context;
    private final LocalServerSocket listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    AppControl(Context context) throws IOException {
        this.context = context;
        listener = new LocalServerSocket("dev.quest.runtime.control");
        worker.execute(this::serve);
    }
    private void serve() {
        while (!closed) {
            try (LocalSocket client = listener.accept()) {
                client.setSoTimeout(3000);
                if (client.getPeerCredentials().getUid() != Process.myUid()) continue;
                InputStream in = client.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                boolean complete = false;
                for (int i = 0; i < 4096; i++) {
                    int b = in.read();
                    if (b == '\n') { complete = true; break; }
                    if (b < 0) break;
                    bytes.write(b);
                }
                JSONObject reply;
                try {
                    if (!complete) throw new IOException("Expected a short newline-terminated request");
                    JSONObject request = new JSONObject(bytes.toString("UTF-8"));
                    if (request.getString("action").startsWith("native-")) {
                        reply = new NativeApps(context).handle(request);
                    } else if (request.getString("action").startsWith("app-")) {
                        // APK parsing/copying and Binder calls must not block the UI thread.
                        reply = handleApp(request);
                    } else {
                        FutureTask<JSONObject> task = new FutureTask<>(() -> handle(request));
                        main.post(task);
                        try { reply = task.get(5, TimeUnit.SECONDS); }
                        catch (Exception e) { task.cancel(false); throw e; }
                    }
                } catch (Exception e) {
                    reply = new JSONObject();
                    try { reply.put("ok", false).put("error", e.toString()); }
                    catch (Exception ignored) {}
                }
                client.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (!closed) Log.w("QuestRuntimeControl", "Local control request failed", e);
            }
        }
    }
    private JSONObject handle(JSONObject request) throws Exception {
        String action = request.getString("action");
        if (!action.equals("check") && !action.equals("launch"))
            throw new IllegalArgumentException("Expected check or launch");
        ComponentName component = ComponentName.unflattenFromString(request.getString("component"));
        if (component == null) throw new IllegalArgumentException("Expected package/activity");
        ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
        if (!info.enabled || !info.applicationInfo.enabled)
            throw new IllegalArgumentException("Activity or app is disabled");
        if (!info.exported && info.applicationInfo.uid != Process.myUid())
            throw new SecurityException("Activity is not exported to this app");
        if (action.equals("launch")) {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return new JSONObject().put("ok", true).put("component", component.flattenToShortString())
            .put("result", action.equals("launch") ? "launch requested" : "activity available");
    }
    private JSONObject handleApp(JSONObject request) throws Exception {
        String action=request.getString("action");
        String value=request.optString("value", "");
        BlackBoxCore core=BlackBoxCore.get();
        JSONObject out=new JSONObject().put("ok",true);
        if(action.equals("app-install")) {
            File apk=new File(value).getCanonicalFile();
            if(!apk.isFile() || !apk.canRead())throw new IOException("APK is not a readable file: " + apk);
            // Install into BlackBox's private registry. Never invoke Android's PackageInstaller.
            InstallResult result=core.installPackageAsUser(apk,0);
            return out.put("ok",result.success).put("package",result.packageName)
                .put("message",result.msg).put("result","private container import");
        }
        if(action.equals("app-list")) {
            JSONArray apps=new JSONArray();
            for(ApplicationInfo info:core.getInstalledApplications(0,0))
                apps.put(new JSONObject().put("package",info.packageName).put("apk",info.sourceDir).put("data",info.dataDir));
            return out.put("apps",apps);
        }
        if(!value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Expected package name");
        if(!core.isInstalled(value,0))throw new IllegalArgumentException("Not imported into container: "+value);
        if(action.equals("app-launch")) {
            boolean requested=core.launchApk(value,0);
            return out.put("ok",requested).put("package",value).put("result",requested?"launch requested":"no launcher activity");
        }
        if(action.equals("app-stop")){core.stopPackage(value,0);return out.put("result","guest stopped");}
        if(action.equals("app-remove")){core.uninstallPackageAsUser(value,0);return out.put("result","guest and its data removed");}
        throw new IllegalArgumentException("Unknown app operation");
    }
    public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        worker.shutdownNow();
    }
}
