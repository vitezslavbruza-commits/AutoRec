package cz.dashcam.auto;

import android.content.*;
import android.os.BatteryManager;
import android.os.Build;

public class PowerReceiver extends BroadcastReceiver {
    // true = po odpojeni nabijecky se nahravani zastavi
    static final boolean STOP_ON_UNPLUG = true;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        Intent s = new Intent(c, RecordService.class);

        if (a.equals(Intent.ACTION_POWER_DISCONNECTED)) {
            if (!STOP_ON_UNPLUG) return;
            s.setAction(RecordService.ACTION_STOP);
        } else {
            if (a.equals(Intent.ACTION_BOOT_COMPLETED)) {
                // po startu telefonu nahravej jen kdyz je zrovna zapojena nabijecka
                Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null || b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return;
            }
            s.setAction(RecordService.ACTION_START);
        }
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    }
}
