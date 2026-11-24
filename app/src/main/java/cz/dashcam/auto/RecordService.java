package cz.dashcam.auto;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class RecordService extends Service {
    static final String ACTION_START = "cz.dashcam.auto.START";
    static final String ACTION_STOP = "cz.dashcam.auto.STOP";
    static final String ACTION_MARK = "cz.dashcam.auto.MARK";

    static final int SEGMENT_MS = 5 * 60 * 1000;          // delka jednoho souboru
    static final long KEEP_DAYS = 30;                       // starsi nez 30 dni se maze
    static final long MIN_FREE_BYTES = 1024L * 1024 * 1024; // pod 1 GB volneho mista maze nejstarsi

    static volatile boolean running = false;
    static volatile String currentName = null;
    static volatile long segStart = 0;

    private Camera camera;
    private MediaRecorder rec;
    private WindowManager wm;
    private SurfaceView sv;
    private PowerManager.WakeLock wl;

    // ---------- pomocne statické funkce ----------
    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "Nahravky");
        d.mkdirs();
        return d;
    }

    static File eventsFile(Context c) { return new File(dir(c), "udalosti.txt"); }

    static void logLine(Context c, String line) {
        try (FileWriter w = new FileWriter(eventsFile(c), true)) {
            w.write(line + "\n");
        } catch (IOException ignored) { }
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** Oznaci udalost: zapise cas, nazev videa a pozici ve videu. */
    static void mark(Context c) {
        String line;
        if (running && currentName != null) {
            long s = (SystemClock.elapsedRealtime() - segStart) / 1000;
            line = now() + " | " + currentName + " | pozice ve videu " + (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
        } else {
            line = now() + " | nenahrává se";
        }
        logLine(c, line);
        Toast.makeText(c, "Událost označena", Toast.LENGTH_SHORT).show();
        Vibrator v = (Vibrator) c.getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(150);
    }

    static void cleanup(Context c) {
        File d = dir(c);
        File[] fs = d.listFiles();
        if (fs == null) return;
        long cut = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
        for (File f : fs) {
            if (f.getName().startsWith("rec_") && f.lastModified() < cut) f.delete();
        }
        // kdyz dochazi misto, smaz nejstarsi
        while (d.getUsableSpace() < MIN_FREE_BYTES) {
            File oldest = null;
            File[] left = d.listFiles();
            if (left == null) break;
            for (File f : left) {
                if (!f.getName().startsWith("rec_") || f.getName().equals(currentName)) continue;
                if (oldest == null || f.lastModified() < oldest.lastModified()) oldest = f;
            }
            if (oldest == null || !oldest.delete()) break;
        }
    }

    // ---------- zivotni cyklus sluzby ----------
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_MARK.equals(a)) {
            mark(this);
            if (!running) stopSelf();
            return START_STICKY;
        }
        startForeground(1, buildNotification());
        if (!running) startRecordingFlow();
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("rec", "Nahrávání", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent m = new Intent(this, RecordService.class).setAction(ACTION_MARK);
        PendingIntent pm = PendingIntent.getService(this, 1, m, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "rec") : new Notification.Builder(this);
        b.setContentTitle("AutoRec nahrává")
                .setContentText("Klepni na tlačítko pro označení události")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_mylocation, "OZNAČIT UDÁLOST", pm);
        return b.build();
    }

    private void startRecordingFlow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            logLine(this, now() + " | CHYBA: chybí povolení 'zobrazení přes jiné aplikace'");
            stopSelf();
            return;
        }
        PowerManager pmgr = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pmgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autorec:rec");
        wl.acquire();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sv = new SurfaceView(this);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { startSegment(); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { }
        });
        try {
            wm.addView(sv, lp);
        } catch (Exception e) {
            logLine(this, now() + " | CHYBA okna: " + e);
            stopAll();
            stopSelf();
        }
    }

    private void startSegment() {
        try {
            releaseRecorder();
            cleanup(this);
            if (camera == null) {
                camera = Camera.open(0);
                camera.setPreviewDisplay(sv.getHolder());
                camera.startPreview();
            }
            camera.unlock();

            rec = new MediaRecorder();
            rec.setCamera(camera);
            rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            rec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            int q = CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_720P)
                    ? CamcorderProfile.QUALITY_720P : CamcorderProfile.QUALITY_HIGH;
            rec.setProfile(CamcorderProfile.get(0, q));

            String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            rec.setOutputFile(new File(dir(this), name).getAbsolutePath());
            rec.setPreviewDisplay(sv.getHolder().getSurface());
            rec.setMaxDuration(SEGMENT_MS);
            rec.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) startSegment();
            });
            rec.prepare();
            rec.start();
            currentName = name;
            segStart = SystemClock.elapsedRealtime();
            running = true;
        } catch (Exception e) {
            logLine(this, now() + " | CHYBA nahrávání: " + e);
            stopAll();
            stopSelf();
        }
    }

    private void releaseRecorder() {
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.reset(); rec.release(); } catch (Exception ignored) { }
            rec = null;
        }
        if (camera != null) {
            try { camera.lock(); } catch (Exception ignored) { }
        }
    }

    private void stopAll() {
        running = false;
        releaseRecorder();
        currentName = null;
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
            camera.release();
            camera = null;
        }
        if (wm != null && sv != null) {
            try { wm.removeView(sv); } catch (Exception ignored) { }
            sv = null;
        }
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
}
