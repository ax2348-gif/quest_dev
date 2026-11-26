package dev.quest.runtime;

import android.app.*;
import android.os.*;
import android.content.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    /** Native apps run in this APK's :xr process and use its permissions (quest_env BODY_TRACKING.md). */
    static final String BODY_TRACKING = "com.oculus.permission.BODY_TRACKING";
    private TextView bodyStatus;
    private final Handler handler = new Handler();
    private TextView status;
    private LinearLayout terminals;
    private String terminalState="";
    private final Runnable refresh = new Runnable() {
        public void run() {
            try {
                status.setText(new String(Files.readAllBytes(new File(getFilesDir(),
                    "status.txt").toPath()), StandardCharsets.UTF_8));
            } catch (IOException e) { status.setText("Waiting for runtime service…"); }
            refreshTerminals();
            refreshBodyStatus();
            handler.postDelayed(this, 1500);
        }
    };
    public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,32,32,32);
        TextView title = new TextView(this);
        title.setText("Quest developer runtime"); title.setTextSize(26); layout.addView(title);
        status = new TextView(this); status.setTextSize(18); layout.addView(status);
        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("Start SSH automatically after reboot and app updates");
        autoStart.setChecked(getSharedPreferences("runtime", MODE_PRIVATE)
            .getBoolean("start_on_boot", true));
        autoStart.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putBoolean("start_on_boot", checked).apply());
        layout.addView(autoStart);
        addButton(layout, "Start SSH", () -> startForegroundService(new Intent(this, RuntimeService.class)));
        addButton(layout, "Stop SSH listener", () -> stopService(new Intent(this, RuntimeService.class)));
        addButton(layout, "Open selected native app", () -> startActivity(
            new Intent().setClassName(this, "android.app.NativeActivity")));
        addButton(layout, "Open new terminal", () -> TerminalActivity.open(this,null));
        bodyStatus = new TextView(this); bodyStatus.setTextSize(16); layout.addView(bodyStatus);
        addButton(layout, "Allow body tracking", () -> requestPermissions(new String[]{BODY_TRACKING}, 1));
        terminals=new LinearLayout(this);terminals.setOrientation(LinearLayout.VERTICAL);
        layout.addView(terminals);
        TextView note = new TextView(this);
        note.setText("\nSSH uses public keys on port 8022: your configured Tailscale address, and loopback for adb forwarding.\n"
            + "Workspace persists across app restarts. Uninstalling erases it.\n"
            + "Running the service keeps the CPU awake and uses battery.");
        layout.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        startForegroundService(new Intent(this, RuntimeService.class));
        // Ask once for body tracking (native apps forward it when started with --body); the button asks again.
        SharedPreferences prefs = getSharedPreferences("runtime", MODE_PRIVATE);
        if (!bodyTrackingGranted() && !prefs.getBoolean("asked_body_tracking", false)) {
            prefs.edit().putBoolean("asked_body_tracking", true).apply();
            requestPermissions(new String[]{BODY_TRACKING}, 1);
        }
    }
    private boolean bodyTrackingGranted() {
        return checkSelfPermission(BODY_TRACKING) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refreshBodyStatus();
    }
    private void refreshBodyStatus() {
        if (bodyStatus != null) bodyStatus.setText("\nBody tracking for native apps: "
            + (bodyTrackingGranted() ? "allowed" : "not allowed (Allow body tracking, or the setup wizard grants it)"));
    }
    private void addButton(LinearLayout l, String text, Runnable r) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> r.run()); l.addView(b);
    }
    private void refreshTerminals(){
        java.util.List<TerminalSession> sessions=TerminalSession.list();
        StringBuilder state=new StringBuilder();
        for(TerminalSession session:sessions)state.append(session.id).append(session.isRunning());
        if(state.toString().equals(terminalState))return;
        terminalState=state.toString();terminals.removeAllViews();
        for(TerminalSession session:sessions)
            addButton(terminals,"Reopen "+session.label+(session.isRunning()?"":" (shell exited)"),
                ()->TerminalActivity.open(this,session.id));
    }
    public void onResume() { super.onResume(); handler.post(refresh); }
    public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
