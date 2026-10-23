package com.ddlradar.app;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import com.getcapacitor.JSObject;
import java.security.MessageDigest;

/** Read-only diagnostics. Signing identity does not grant store provenance. */
final class AppEnvironment {
    static JSObject read(Context context) {
        JSObject result = new JSObject();
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), flags);
            result.put("debuggable", (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            android.content.pm.Signature[] certificates = Build.VERSION.SDK_INT >= 28
                    ? info.signingInfo.getApkContentsSigners() : info.signatures;
            if (certificates != null && certificates.length > 0) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray());
                StringBuilder fingerprint = new StringBuilder();
                for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                result.put("signerSha256", fingerprint.toString());
            }
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.pm.InstallSourceInfo source = manager.getInstallSourceInfo(context.getPackageName());
                result.put("installer", source.getInstallingPackageName());
                if (Build.VERSION.SDK_INT >= 33) result.put("packageSource", source.getPackageSource());
            } else result.put("installer", manager.getInstallerPackageName(context.getPackageName()));
        } catch (Exception error) { result.put("packageInfoError", error.toString()); }
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && activity != null) result.put("backgroundRestricted", activity.isBackgroundRestricted());
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            result.put("ringerMode", audio.getRingerMode());
            result.put("notificationVolume", audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION));
        }
        result.put("notificationVibrationIntensity", android.provider.Settings.System.getInt(
                context.getContentResolver(), "notification_vibration_intensity", -1));
        if (power != null) {
            result.put("powerSaveMode", power.isPowerSaveMode());
            if (Build.VERSION.SDK_INT >= 23) result.put("batteryExempt", power.isIgnoringBatteryOptimizations(context.getPackageName()));
        }
        AppOpsManager operations = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (operations != null) {
            try { result.put("vibrateAppOp", operations.checkOpNoThrow("android:vibrate", android.os.Process.myUid(), context.getPackageName())); }
            catch (Exception error) { result.put("vibrateAppOpError", error.toString()); }
        }
        return result;
    }
}
