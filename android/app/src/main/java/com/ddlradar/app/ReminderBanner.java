package com.ddlradar.app;

import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/** User-authorized top banner; each reminder has its own close button/timer. */
final class ReminderBanner {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Integer, View> CARDS = new HashMap<>();
    private static final Map<Integer, Runnable> TIMERS = new HashMap<>();
    private static WindowManager windows;
    private static ScrollView window;
    private static LinearLayout stack;

    private static final class BannerScroll extends ScrollView {
        BannerScroll(Context context) { super(context); }
        @Override protected void onMeasure(int width, int height) {
            int maximum = Math.min(dp(getContext(), 320), getResources().getDisplayMetrics().heightPixels / 2);
            super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST));
        }
    }

    static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    static int visibleCount() { return CARDS.size(); }

    static void show(Context context, int id, String title, String body, boolean autoDismiss) {
        Context app = context.getApplicationContext();
        if (!allowed(app)) { ReminderTrace.mark(app, id & Integer.MAX_VALUE, "overlay_blocked", "permission"); return; } // The ordinary notification remains available.
        MAIN.post(() -> {
            try {
                if (window == null) {
                    windows = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    if (windows == null) return;
                    stack = new LinearLayout(app);
                    stack.setOrientation(LinearLayout.VERTICAL);
                    window = new BannerScroll(app);
                    window.setFillViewport(false);
                    window.addView(stack);
                    int width = Math.min(dp(app, 440), app.getResources().getDisplayMetrics().widthPixels - dp(app, 24));
                    int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                            WindowManager.LayoutParams.WRAP_CONTENT, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    params.y = dp(app, 12);
                    window.setLayoutParams(params);
                }
                remove(id);
                LinearLayout card = new LinearLayout(app);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(app, 16), dp(app, 12), dp(app, 16), dp(app, 12));
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.rgb(35, 42, 70));
                background.setCornerRadius(dp(app, 18));
                card.setBackground(background);
                TextView heading = new TextView(app);
                heading.setText(title);
                heading.setTextSize(17);
                heading.setTextColor(Color.WHITE);
                card.addView(heading);
                TextView detail = new TextView(app);
                detail.setText(body);
                detail.setTextSize(14);
                detail.setMaxLines(4);
                detail.setTextColor(Color.rgb(220, 225, 245));
                card.addView(detail);
                Button close = new Button(app);
                close.setText("知道了 · 关闭");
                close.setOnClickListener(view -> {
                    AlarmDelivery.cancelNotification(app, id);
                    dismiss(id);
                });
                card.addView(close);
                stack.addView(card);
                CARDS.put(id, card);
                if (!window.isAttachedToWindow()) {
                    windows.addView(window, window.getLayoutParams());
                }
                ReminderTrace.mark(app, id & Integer.MAX_VALUE, "overlay_attached", "awaiting_layout");
                final View shownCard = card;
                card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        shownCard.getViewTreeObserver().removeOnPreDrawListener(this);
                        android.graphics.Rect visible = new android.graphics.Rect();
                        boolean drawn = shownCard.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
                        ReminderTrace.mark(app, id & Integer.MAX_VALUE, drawn ? "overlay_visible" : "overlay_hidden",
                                visible.width() + "x" + visible.height());
                        return true;
                    }
                });
                if (autoDismiss) {
                    Runnable timer = () -> dismiss(id);
                    TIMERS.put(id, timer);
                    MAIN.postDelayed(timer, 5000L);
                }
            } catch (Exception error) {
                Log.w("DdlAlarm", "Top banner unavailable; notification retained", error);
                ReminderTrace.mark(app, id & Integer.MAX_VALUE, "overlay_error", error.toString());
                clear();
            }
        });
    }

    private static void remove(int id) {
        Runnable timer = TIMERS.remove(id);
        if (timer != null) MAIN.removeCallbacks(timer);
        View card = CARDS.remove(id);
        if (card != null && stack != null) stack.removeView(card);
    }

    static void dismiss(int id) {
        MAIN.post(() -> { remove(id); if (CARDS.isEmpty()) clear(); AlarmService.bannerChanged(); });
    }

    static void dismissAll() { MAIN.post(ReminderBanner::clear); }

    private static void clear() {
        for (Runnable timer : TIMERS.values()) MAIN.removeCallbacks(timer);
        TIMERS.clear();
        CARDS.clear();
        try { if (windows != null && window != null) windows.removeView(window); }
        catch (Exception ignored) { }
        window = null;
        stack = null;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
