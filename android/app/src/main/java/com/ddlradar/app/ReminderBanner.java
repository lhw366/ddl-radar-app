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
import android.widget.FrameLayout;
import android.util.Log;
import java.util.Calendar;
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
                SeasonTheme season = SeasonTheme.forMonth(Calendar.getInstance().get(Calendar.MONTH));
                FrameLayout card = new FrameLayout(app);
                card.setTag("ddlr-reminder-" + id);
                GradientDrawable background = new GradientDrawable();
                background.setOrientation(GradientDrawable.Orientation.TL_BR);
                background.setColors(new int[]{season.topColor, season.bottomColor});
                background.setCornerRadius(dp(app, 24));
                background.setStroke(dp(app, 1), Color.argb(58, 255, 255, 255));
                card.setBackground(background);
                card.setClipToOutline(true);
                card.setElevation(dp(app, 10));
                card.setMinimumHeight(dp(app, 96));
                SeasonSceneryView scenery = new SeasonSceneryView(app);
                scenery.setTheme(season);
                card.addView(scenery, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                LinearLayout content = new LinearLayout(app);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(app, 15), dp(app, 10), dp(app, 9), dp(app, 12));
                card.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout meta = new LinearLayout(app);
                meta.setOrientation(LinearLayout.HORIZONTAL);
                meta.setGravity(Gravity.CENTER_VERTICAL);
                content.addView(meta, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(app, 32)));
                TextView seasonLabel = new TextView(app);
                seasonLabel.setText(season.mark + "  " + season.kicker);
                seasonLabel.setTextSize(9.5f);
                seasonLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                seasonLabel.setLetterSpacing(.08f);
                seasonLabel.setTextColor(season.accentColor);
                seasonLabel.setGravity(Gravity.CENTER_VERTICAL);
                seasonLabel.setIncludeFontPadding(false);
                meta.addView(seasonLabel, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                TextView close = new TextView(app);
                close.setText("×");
                close.setTextSize(19);
                close.setTextColor(Color.argb(225, 255, 255, 255));
                close.setGravity(Gravity.CENTER);
                close.setIncludeFontPadding(false);
                close.setContentDescription("关闭提醒");
                GradientDrawable closeMask = new GradientDrawable();
                closeMask.setColor(Color.WHITE);
                closeMask.setCornerRadius(dp(app, 20));
                close.setBackground(new RippleDrawable(
                        ColorStateList.valueOf(Color.argb(42, 255, 255, 255)), null, closeMask));
                close.setClickable(true);
                close.setFocusable(true);
                close.setOnClickListener(view -> {
                    AlarmDelivery.cancelNotification(app, id);
                    dismiss(id, "user");
                });
                meta.addView(close, new LinearLayout.LayoutParams(dp(app, 36), dp(app, 36)));
                TextView heading = new TextView(app);
                heading.setText(title);
                heading.setTextSize(16);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setTextColor(Color.rgb(250, 250, 247));
                heading.setIncludeFontPadding(false);
                heading.setMaxLines(2);
                heading.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(heading);
                TextView detail = new TextView(app);
                detail.setText(body);
                detail.setTextSize(12);
                detail.setIncludeFontPadding(false);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                detail.setTextColor(season.detailColor);
                LinearLayout.LayoutParams detailLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                detailLayout.topMargin = dp(app, 5);
                content.addView(detail, detailLayout);
                LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cardLayout.bottomMargin = dp(app, 8);
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
