package app.musemovelog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TrackingService.isEnabled(context)) return;
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            TrackingService.start(context);
        } catch (Exception e) {
            Dbg.log(context, "boot restart failed: " + e);
        }
    }
}
