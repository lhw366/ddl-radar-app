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
    private String originalScreenTimeout;

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
        ringerAfterDndOff(AudioManager.RINGER_MODE_SILENT);
        assertEquals("Silent notifications must have zero effective volume", 0,
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
    }

    void normalWithAudibleNotificationVolume() throws Exception {
        ringerAfterDndOff(AudioManager.RINGER_MODE_NORMAL);
        if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
            shell("cmd audio set-volume 5 1");
            volumeChanged = true;
        }
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0);
    }

    /** The withheld-alarm case tests a cached app while the device is awake.
     * Screen-off system wakeup is covered independently by the cold host case.
     * Only the disposable emulator's display timeout changes, never the APK.
     */
    void keepScreenOnForTimingTest() throws Exception {
        if (originalScreenTimeout == null) originalScreenTimeout = shell("settings get system screen_off_timeout").trim();
        shell("settings put system screen_off_timeout 600000");
        shell("input keyevent KEYCODE_WAKEUP");
    }

    private void ringerAfterDndOff(int ringer) throws Exception {
        // ZenModeHelper restores the prior ringer asynchronously. A NORMAL
        // command issued immediately after "set_dnd off" can be overwritten
        // by that restore (CI 99: streams unmuted, then muted again 3 ms later).
        // Settle this TEST precondition before any reminder is scheduled. A
        // stable readback is required; this never retries product delivery.
        interruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        for (int attempt = 0; attempt < 3; attempt++) {
            shell("cmd audio set-ringer-mode " + ringerName(ringer));
            long until = System.currentTimeMillis() + 1500;
            long stableSince = 0;
            while (System.currentTimeMillis() < until) {
                if (audio.getRingerMode() == ringer && notifications.getCurrentInterruptionFilter()
                        == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    if (stableSince == 0) stableSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - stableSince >= 400) return;
                } else stableSince = 0;
                Thread.sleep(50);
            }
        }
        await(ringer, NotificationManager.INTERRUPTION_FILTER_ALL);
        fail("Ringer/DND setup did not remain stable before the reminder test");
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
        if (originalScreenTimeout != null) {
            if (originalScreenTimeout.matches("\\d+")) shell("settings put system screen_off_timeout " + originalScreenTimeout);
            else shell("settings delete system screen_off_timeout");
        }
        ringerAfterDndOff(originalRinger);
        if (volumeChanged) {
            shell("cmd audio set-ringer-mode NORMAL");
            shell("cmd audio set-volume 5 " + originalNotificationVolume);
        }
        shell("cmd audio set-ringer-mode " + ringerName(originalRinger));
        shell("cmd notification set_dnd " + filterName(originalFilter));
        await(originalRinger, originalFilter);
    }
}
