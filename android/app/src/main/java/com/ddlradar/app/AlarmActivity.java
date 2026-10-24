package com.ddlradar.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small lock-screen card opened through the existing full-screen notification intent. */
public class AlarmActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> closeReminder("timeout", true);
    private int notifId = -1;
    private long expiresAt;
    private boolean receiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Preserve the notification for later; the screen may sleep normally.
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) closeReminder("screen_off", false);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        receiverRegistered = true;
        render(getIntent(), state == null ? 0 : state.getLong("expiresAt", 0));
    }

    private void render(Intent intent, long restoredExpiry) {
        main.removeCallbacks(timeout);
        notifId = intent.getIntExtra("notifId", -1);
        String title = intent.getStringExtra("title"), body = intent.getStringExtra("body");
        if (title == null || title.isEmpty()) title = "DDL雷达 提醒";
        if (body == null) body = "";
        boolean automatic = intent.getBooleanExtra("autoDismiss", false);

        LinearLayout card = new LinearLayout(this);
        card.setTag("ddlr-alarm-card");
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.argb(246, 30, 35, 52));
        surface.setCornerRadius(dp(20));
        surface.setStroke(dp(1), Color.argb(45, 224, 235, 255));
        card.setBackground(surface);

        card.addView(text("DDL雷达 · 到点提醒", 11, Color.rgb(151, 222, 204)));
        TextView heading = text(title, 17, Color.rgb(242, 246, 255));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams headingLayout = new LinearLayout.LayoutParams(-1, -2);
        headingLayout.topMargin = dp(8);
        card.addView(heading, headingLayout);
        TextView detail = text(body, 13, Color.rgb(185, 197, 218));
        detail.setMaxLines(3);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodyLayout = new LinearLayout.LayoutParams(-1, -2);
        bodyLayout.topMargin = dp(6);
        card.addView(detail, bodyLayout);
        if (body.isEmpty()) detail.setVisibility(View.GONE);

        Button acknowledge = new Button(this);
        acknowledge.setTag("ddlr-alarm-ack");
        acknowledge.setText("知道了");
        acknowledge.setTextSize(13);
        acknowledge.setTextColor(Color.rgb(211, 246, 235));
        acknowledge.setAllCaps(false);
        acknowledge.setPadding(0, 0, 0, 0);
        acknowledge.setMinimumHeight(0);
        acknowledge.setMinimumWidth(0);
        acknowledge.setStateListAnimator(null);
        GradientDrawable button = new GradientDrawable();
        button.setColor(Color.rgb(42, 68, 67));
        button.setCornerRadius(dp(14));
        acknowledge.setBackground(button);
        acknowledge.setOnClickListener(view -> closeReminder("user", true));
        LinearLayout.LayoutParams buttonLayout = new LinearLayout.LayoutParams(dp(88), dp(44));
        buttonLayout.gravity = Gravity.END;
        buttonLayout.topMargin = dp(10);
        card.addView(acknowledge, buttonLayout);
        setContentView(card);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.3f);
        window.setGravity(Gravity.CENTER);
        window.setLayout(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                if (notifId != -1) ReminderTrace.mark(AlarmActivity.this, notifId & Integer.MAX_VALUE, "alarm_page_shown",
                        "autoDismiss=" + automatic + ";width=" + card.getWidth() + ";height=" + card.getHeight()
                                + ";keepScreenOn=" + ((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0));
                return true;
            }
        });
        expiresAt = automatic ? (restoredExpiry > 0 ? restoredExpiry : SystemClock.uptimeMillis() + 5000) : 0;
        if (automatic) main.postDelayed(timeout, Math.max(1, expiresAt - SystemClock.uptimeMillis()));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void closeReminder(String reason, boolean acknowledge) {
        if (isFinishing()) return;
        if (notifId != -1) {
            if (acknowledge) {
                AlarmDelivery.cancelNotification(this, notifId);
                ReminderBanner.dismiss(notifId);
            }
            ReminderTrace.mark(this, notifId & Integer.MAX_VALUE, "alarm_page_closed", "reason=" + reason);
        }
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent, 0); // Reuse the small singleTask card without recreating a full-screen page.
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("expiresAt", expiresAt);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(screenOff);
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
