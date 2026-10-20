package dev.quest.uidemo;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;

/** Small permission-free Android UI for remote-launch experiments. */
public class MainActivity extends Activity {
    private int count;
    private int color;
    private final int[] colors = {
        Color.rgb(32, 124, 128), Color.rgb(83, 64, 160), Color.rgb(177, 91, 40)
    };
    private TextView counter;
    private TextView swatch;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        count = getPreferences(MODE_PRIVATE).getInt("count", 0);
        color = getPreferences(MODE_PRIVATE).getInt("color", 0);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(36), dp(32), dp(36), dp(32));
        body.setBackgroundColor(Color.rgb(245, 247, 250));
        TextView title = text("Hello from an uninstalled APK", 30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(title);
        body.addView(text("Running inside the Quest APK container.", 17));

        swatch = text("Quest UI demo", 22);
        swatch.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams panel = new LinearLayout.LayoutParams(-1, dp(130));
        panel.setMargins(0, dp(24), 0, dp(16));
        body.addView(swatch, panel);
        counter = text("", 24);
        body.addView(counter);
        Button tap = new Button(this); tap.setText("Tap counter");
        tap.setOnClickListener(v -> { count++; update(); });
        body.addView(tap, new LinearLayout.LayoutParams(-1, dp(56)));
        Button change = new Button(this); change.setText("Change color");
        change.setOnClickListener(v -> { color = (color + 1) % colors.length; update(); });
        body.addView(change, new LinearLayout.LayoutParams(-1, dp(56)));
        body.addView(text("Package: " + getPackageName() + "\nAndroid app UID: "
            + Process.myUid() + "\nCounter and color survive closing this window.", 15));
        Button close = new Button(this); close.setText("Close demo");
        close.setOnClickListener(v -> finish());
        body.addView(close);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        setContentView(scroll);
        update();
        android.util.Log.i("GuestProof", "onCreate package=" + getPackageName() + " uid=" + Process.myUid() + " data=" + getFilesDir());
    }
    private void update() {
        counter.setText("Taps: " + count);
        swatch.setBackgroundColor(colors[color]);
        getPreferences(MODE_PRIVATE).edit().putInt("count", count).putInt("color", color).apply();
    }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setGravity(Gravity.CENTER); t.setTextColor(Color.rgb(30, 40, 55));
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
