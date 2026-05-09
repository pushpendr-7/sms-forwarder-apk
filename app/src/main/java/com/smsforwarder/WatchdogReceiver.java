package com.smsforwarder;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.List;

public class WatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!isServiceRunning(context)) {
            Intent s = new Intent(context, ForwarderService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(s);
            } else {
                context.startService(s);
            }
        }
        WatchdogScheduler.schedule(context);
    }

    private boolean isServiceRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(50);
        for (ActivityManager.RunningServiceInfo info : services) {
            if (ForwarderService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
