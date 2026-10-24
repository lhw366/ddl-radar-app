package com.ddlradar.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/** User-authorized top banner; each reminder has its own close button/timer. */
final class ReminderBanner {
    static final int SURFACE_COLOR = Color.argb(240, 30, 35, 52);
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
                    int width = Math.min(dp(app, 360), app.getResources().getDisplayMetrics().widthPixels - dp(app, 24));
                    int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                            WindowManager.LayoutParams.WRAP_CONTENT, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    params.y = dp(app, 10);
                    params.setTitle("DDL雷达 · 顶部提醒");
                    window.setLayoutParams(params);
                }
                remove(id);
                LinearLayout card = new LinearLayout(app);
                card.setTag("ddlr-reminder-" + id);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(app, 12), dp(app, 8), dp(app, 6), dp(app, 8));
                card.setMinimumHeight(dp(app, 60));
                GradientDrawable background = new GradientDrawable();
                background.setColor(SURFACE_COLOR);
                background.setCornerRadius(dp(app, 16));
                background.setStroke(dp(app, 1), Color.argb(42, 224, 235, 255));
                card.setBackground(background);
                View accent = new View(app);
                GradientDrawable accentColor = new GradientDrawable();
                accentColor.setColor(Color.rgb(151, 222, 204));
                accentColor.setCornerRadius(dp(app, 2));
                accent.setBackground(accentColor);
                LinearLayout.LayoutParams accentLayout = new LinearLayout.LayoutParams(dp(app, 3), dp(app, 26));
                accentLayout.rightMargin = dp(app, 11);
                card.addView(accent, accentLayout);
                LinearLayout copy = new LinearLayout(app);
                copy.setOrientation(LinearLayout.VERTICAL);
                card.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                TextView heading = new TextView(app);
                heading.setText(title);
                heading.setTextSize(15);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setTextColor(Color.rgb(242, 246, 255));
                heading.setIncludeFontPadding(false);
                heading.setSingleLine(true);
                heading.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(heading);
                TextView detail = new TextView(app);
                detail.setText(body);
                detail.setTextSize(12);
                detail.setIncludeFontPadding(false);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                detail.setTextColor(Color.rgb(185, 197, 218));
                LinearLayout.LayoutParams detailLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                detailLayout.topMargin = dp(app, 4);
                copy.addView(detail, detailLayout);
                TextView close = new TextView(app);
                close.setText("×");
                close.setTextSize(22);
                close.setTextColor(Color.rgb(202, 213, 230));
                close.setGravity(Gravity.CENTER);
                close.setIncludeFontPadding(false);
                close.setContentDescription("关闭提醒");
                GradientDrawable closeMask = new GradientDrawable();
                closeMask.setColor(Color.WHITE);
                closeMask.setCornerRadius(dp(app, 22));
                close.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), null, closeMask));
                close.setClickable(true);
                close.setFocusable(true);
                close.setOnClickListener(view -> {
                    AlarmDelivery.cancelNotification(app, id);
                    dismiss(id, "user");
                });
                card.addView(close, new LinearLayout.LayoutParams(dp(app, 44), dp(app, 44)));
                LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cardLayout.bottomMargin = dp(app, 6);
                stack.addView(card, cardLayout);
                CARDS.put(id, card);
                if (!window.isAttachedToWindow()) {
                    windows.addView(window, window.getLayoutParams());
                }
                ReminderTrace.mark(app, id & Integer.MAX_VALUE, "overlay_attached", "appVisible=" + MainActivity.visible
                        + ";attached=" + window.isAttachedToWindow() + ";windowType=" + ((WindowManager.LayoutParams) window.getLayoutParams()).type);
                final View shownCard = card;
                card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        shownCard.getViewTreeObserver().removeOnPreDrawListener(this);
                        android.graphics.Rect visible = new android.graphics.Rect();
                        boolean drawn = shownCard.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
                        int[] position = new int[2], closePosition = new int[2];
                        shownCard.getLocationOnScreen(position);
                        close.getLocationOnScreen(closePosition);
                        ReminderTrace.mark(app, id & Integer.MAX_VALUE, drawn ? "overlay_visible" : "overlay_hidden",
                                "appVisible=" + MainActivity.visible + ";x=" + position[0] + ";y=" + position[1]
                                + ";width=" + shownCard.getWidth() + ";height=" + shownCard.getHeight()
                                + ";closeX=" + closePosition[0] + ";closeY=" + closePosition[1]
                                + ";closeWidth=" + close.getWidth() + ";closeHeight=" + close.getHeight()
                                + ";attached=" + shownCard.isAttachedToWindow() + ";shown=" + shownCard.isShown());
                        return true;
                    }
                });
                if (autoDismiss) {
                    Runnable timer = () -> dismiss(id, "timeout");
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

    static void dismiss(int id) { dismiss(id, "acknowledged"); }

    private static void dismiss(int id, String reason) {
        MAIN.post(() -> {
            View card = CARDS.get(id);
            if (card != null) ReminderTrace.mark(card.getContext(), id & Integer.MAX_VALUE, "overlay_dismissed",
                    "reason=" + reason + ";appVisible=" + MainActivity.visible);
            remove(id);
            if (CARDS.isEmpty()) clear();
            AlarmService.bannerChanged();
        });
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
