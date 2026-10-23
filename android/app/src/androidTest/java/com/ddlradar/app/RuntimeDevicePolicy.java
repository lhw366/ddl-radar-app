package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.ParcelFileDescriptor;
import static org.junit.Assert.*;

/** Test-only system controls: the host and this helper both require a disposable emulator. */
final class RuntimeDevicePolicy implements AutoCloseable {
    final AudioManager audio;
    final NotificationManager notifications;
    private final Instrumentation instrumentation;
    private final int originalRinger;
    private final int originalFilter;
    private final int originalNotificationVolume;
    private boolean volumeChanged;

    RuntimeDevicePolicy(Context context, Instrumentation instrumentation) throws Exception {
        this.instrumentation = instrumentation;
        assertEquals("Never change a physical phone's system settings", "1", shell("getprop ro.kernel.qemu").trim());
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        originalRinger = audio.getRingerMode();
        originalFilter = notifications.getCurrentInterruptionFilter();
        originalNotificationVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
    }

    void silentWithAllInterruptions() throws Exception {
        shell("cmd notification set_dnd off");
        shell("cmd audio set-ringer-mode SILENT");
        await(AudioManager.RINGER_MODE_SILENT, NotificationManager.INTERRUPTION_FILTER_ALL);
        assertEquals("Silent notifications must have zero effective volume", 0,
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
    }

    void normalWithAudibleNotificationVolume() throws Exception {
        shell("cmd notification set_dnd off");
        shell("cmd audio set-ringer-mode NORMAL");
        await(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL);
        if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
            shell("cmd audio set-volume 5 1");
            volumeChanged = true;
        }
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0);
    }

    void interruptionFilter(int filter) throws Exception {
        shell("cmd notification set_dnd " + filterName(filter));
        long until = System.currentTimeMillis() + 2500;
        while (notifications.getCurrentInterruptionFilter() != filter && System.currentTimeMillis() < until)
            Thread.sleep(50);
        assertEquals("DND command must change the actual framework state", filter,
                notifications.getCurrentInterruptionFilter());
    }

    private void await(int ringer, int filter) throws Exception {
        long until = System.currentTimeMillis() + 3000;
        while ((audio.getRingerMode() != ringer || notifications.getCurrentInterruptionFilter() != filter)
                && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals("Actual ringer state", ringer, audio.getRingerMode());
        assertEquals("Actual DND filter", filter, notifications.getCurrentInterruptionFilter());
    }

    private static String ringerName(int mode) {
        return mode == AudioManager.RINGER_MODE_SILENT ? "SILENT"
                : mode == AudioManager.RINGER_MODE_VIBRATE ? "VIBRATE" : "NORMAL";
    }

    private static String filterName(int filter) {
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ? "none"
                : filter == NotificationManager.INTERRUPTION_FILTER_ALARMS ? "alarms"
                : filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ? "priority" : "off";
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }

    @Override public void close() throws Exception {
        // Only the fixture changed these settings. Product code never writes them.
        shell("cmd notification set_dnd off");
        if (volumeChanged) {
            shell("cmd audio set-ringer-mode NORMAL");
            shell("cmd audio set-volume 5 " + originalNotificationVolume);
        }
        shell("cmd audio set-ringer-mode " + ringerName(originalRinger));
        shell("cmd notification set_dnd " + filterName(originalFilter));
        await(originalRinger, originalFilter);
    }
}
