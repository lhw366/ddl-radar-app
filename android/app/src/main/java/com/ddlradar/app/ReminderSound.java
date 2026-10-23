package com.ddlradar.app;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/** One finite cue owned by the delivery service, never a background audio loop. */
final class ReminderSound {
    private static MediaPlayer player;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable timeout = () -> stop();

    static boolean canPlay(Context context, String channel) {
        if (channel == null || !channel.endsWith("_sound")) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
                && (manager == null || manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    static void play(Context context, int id, String channel) {
        if (channel == null || !channel.endsWith("_sound")) return;
        handler.post(() -> {
            stop();
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            // Respect the phone's silent/vibrate mode, notification volume and DND.
            if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
                    || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0
                    || (notifications != null && notifications.getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_ALL)) {
                String reason = audio == null ? "audio_unavailable"
                        : audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT ? "ringer_silent"
                        : audio.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ? "ringer_vibrate"
                        : audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0 ? "notification_volume_zero" : "do_not_disturb";
                ReminderTrace.mark(context, id, "sound_suppressed", "reason=" + reason);
                return;
            }
            try {
                MediaPlayer cue = new MediaPlayer();
                player = cue;
                cue.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                try (android.content.res.AssetFileDescriptor file = context.getResources().openRawResourceFd(R.raw.ddlr_chime)) {
                    cue.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                }
                cue.setLooping(false);
                cue.setOnCompletionListener(done -> {
                    ReminderTrace.mark(context, id, "sound_completed", "finite_cue");
                    if (player == done) stop();
                });
                cue.setOnErrorListener((failed, what, extra) -> {
                    ReminderTrace.mark(context, id, "sound_error", what + ":" + extra);
                    if (player == failed) stop();
                    return true;
                });
                cue.prepare();
                cue.start();
                ReminderTrace.mark(context, id, "sound_started", "playing=" + cue.isPlaying()
                        + ";durationMs=" + cue.getDuration()
                        + ";volume=" + audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
                handler.postDelayed(timeout, 3500L);
            } catch (Exception error) {
                ReminderTrace.mark(context, id, "sound_error", error.toString());
                stop();
            }
        });
    }

    static void stop() {
        handler.removeCallbacks(timeout);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
