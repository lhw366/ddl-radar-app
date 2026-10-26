package com.ddlradar.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import java.util.Locale;

/** User-opened startup access only. Never writes power policies or claims access is granted. */
final class OemStartupSettings {
    // Known public startup pages documented by judemanutd/AutoStarter (MIT).
    // https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt
    private static final String[][] COLOROS = {
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"}
    };

    static boolean isColorOs() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus");
    }

    private static Intent startupIntent(String[] component) {
        return new Intent().setComponent(new ComponentName(component[0], component[1]))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static JSObject status(Context context) {
        boolean available = false;
        if (isColorOs()) for (String[] component : COLOROS) {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(startupIntent(component), 0);
            if (resolved != null && resolved.activityInfo != null && resolved.activityInfo.exported) available = true;
        }
        return new JSObject().put("colorOS", isColorOs()).put("state", "unknown")
                .put("startupPageAvailable", available).put("requiresUserCheck", isColorOs());
    }

    static JSObject open(Context context) {
        if (isColorOs()) for (String[] component : COLOROS) {
            try {
                context.startActivity(startupIntent(component));
                return new JSObject().put("opened", true).put("page", "startup").put("state", "unknown");
            } catch (Exception unavailable) { /* Newer ROMs may hide or protect this page. */ }
        }
        context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return new JSObject().put("opened", true).put("page", "app_details").put("state", "unknown");
    }

    private OemStartupSettings() { }
}
