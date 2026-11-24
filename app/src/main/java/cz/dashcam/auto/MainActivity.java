package cz.dashcam.auto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

import java.io.*;

public class MainActivity extends Activity {
    private TextView status, log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        root.addView(button("Povolit oprávnění", v -> askPermissions()));
        root.addView(button("Spustit nahrávání", v -> send(RecordService.ACTION_START)));
        root.addView(button("Zastavit nahrávání", v -> send(RecordService.ACTION_STOP)));

        Button mark = button("OZNAČIT UDÁLOST", v -> { RecordService.mark(this); showLog(); });
        mark.setTextSize(22);
        mark.setPadding(0, 60, 0, 60);
        root.addView(mark);

        TextView h = new TextView(this);
        h.setText("\nZáznam událostí:");
        root.addView(h);
        log = new TextView(this);
        ScrollView sc = new ScrollView(this);
        sc.addView(log);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        askPermissions();
    }

    private Button button(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setOnClickListener(l);
        return x;
    }

    private void send(String action) {
        Intent i = new Intent(this, RecordService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && !RecordService.ACTION_STOP.equals(action)) startForegroundService(i);
        else startService(i);
        status.postDelayed(this::refresh, 1500);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
                return;
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Povol 'Zobrazení přes jiné aplikace'", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) { askPermissions(); }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        status.setText(RecordService.running ? "● NAHRÁVÁ: " + RecordService.currentName : "○ Nenahrává");
        showLog();
    }

    private void showLog() {
        File f = RecordService.eventsFile(this);
        StringBuilder sb = new StringBuilder();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l;
                while ((l = r.readLine()) != null) sb.insert(0, l + "\n"); // nejnovejsi nahore
            } catch (IOException ignored) { }
        }
        log.setText(sb.length() == 0 ? "(zatím nic)" : sb.toString());
    }
}
