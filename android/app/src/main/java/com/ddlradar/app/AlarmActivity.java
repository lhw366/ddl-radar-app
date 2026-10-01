package com.ddlradar.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 闹钟式全屏提醒页：系统闹钟响铃时的同款体验——
 * 锁屏/灭屏状态下由通知的全屏 Intent（fullScreenIntent）直接拉起，
 * 亮屏、越过锁屏显示，确保后台/锁屏时的提醒「看得见」。
 */
public class AlarmActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManagerFlags.showWhenLocked | WindowManagerFlags.turnScreenOn);
        }

        String title = getIntent().getStringExtra("title");
        String body = getIntent().getStringExtra("body");
        if (title == null || title.isEmpty()) title = "DDL雷达 提醒";
        if (body == null) body = "";

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#1b2140"));

        TextView icon = new TextView(this);
        icon.setText("⏰");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(22);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(20), 0, dp(8));
        root.addView(tv);

        TextView bv = new TextView(this);
        bv.setText(body);
        bv.setTextSize(15);
        bv.setTextColor(Color.parseColor("#c9cff2"));
        bv.setGravity(Gravity.CENTER);
        bv.setPadding(0, 0, 0, dp(28));
        root.addView(bv);

        Button ok = new Button(this);
        ok.setText("知道了");
        ok.setTextColor(Color.WHITE);
        ok.setTextSize(16);
        ok.setBackground(rounded("#5b6bd6"));
        ok.setPadding(dp(40), dp(12), dp(40), dp(12));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(ok, lp);

        setContentView(root);
        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManagerFlags.keepScreenOn);
            w.setStatusBarColor(Color.parseColor("#1b2140"));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable rounded(String color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(24));
        return d;
    }

    /** API 27 以下的窗口标志位（27+ 用 setShowWhenLocked/setTurnScreenOn） */
    private static final class WindowManagerFlags {
        static final int showWhenLocked = 0x00080000; // FLAG_SHOW_WHEN_LOCKED
        static final int turnScreenOn = 0x00600000;   // FLAG_TURN_SCREEN_ON
        static final int keepScreenOn = 0x00000080;   // FLAG_KEEP_SCREEN_ON
    }
}
