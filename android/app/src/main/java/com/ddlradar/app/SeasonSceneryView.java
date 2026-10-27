package com.ddlradar.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Lightweight canvas scenery; no bitmap loading or background work. */
final class SeasonSceneryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private SeasonTheme theme = SeasonTheme.forMonth(9);

    SeasonSceneryView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setTheme(SeasonTheme value) {
        theme = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = Math.max(1, getHeight());
        float radius = 24f * getResources().getDisplayMetrics().density;
        path.reset();
        path.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        paint.setShader(new LinearGradient(0, 0, w, h, theme.topColor, theme.bottomColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        paint.setColor(withAlpha(theme.accentColor, 155));
        canvas.drawCircle(w - dp(43), dp(25), dp(15), paint);
        paint.setColor(withAlpha(Color.WHITE, 46));
        canvas.drawCircle(w - dp(47), dp(21), dp(9), paint);

        String name = theme.name;
        if ("春日".equals(name)) drawSpring(canvas, w, h);
        else if ("夏日".equals(name)) drawSummer(canvas, w, h);
        else if ("秋日".equals(name)) drawAutumn(canvas, w, h);
        else drawWinter(canvas, w, h);
        canvas.restore();
    }

    private void drawSpring(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(58, 167, 224, 176));
        canvas.drawCircle(w * .18f, h + dp(8), h * .58f, paint);
        canvas.drawCircle(w * .56f, h + dp(12), h * .46f, paint);
        paint.setColor(Color.argb(145, 255, 220, 228));
        for (int i = 0; i < 7; i++) {
            canvas.drawCircle(w * .22f + i * dp(18), dp(19) + (i % 3) * dp(17), dp(1.4f), paint);
        }
    }

    private void drawSummer(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(68, 117, 214, 221));
        path.reset();
        path.moveTo(0, h * .72f);
        path.cubicTo(w * .25f, h * .52f, w * .48f, h * .86f, w, h * .62f);
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(70, 225, 250, 250));
        canvas.drawLine(dp(18), h * .55f, w * .72f, h * .55f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawAutumn(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(66, 102, 62, 31));
        canvas.drawCircle(w * .18f, h + dp(14), h * .58f, paint);
        canvas.drawCircle(w * .62f, h + dp(18), h * .43f, paint);
        paint.setColor(Color.argb(145, 255, 187, 93));
        for (int i = 0; i < 8; i++) {
            float x = w * .16f + i * dp(20), y = dp(18) + (i % 4) * dp(14);
            canvas.save();
            canvas.rotate(32 + i * 9, x, y);
            canvas.drawOval(x - dp(2), y - dp(4), x + dp(2), y + dp(4), paint);
            canvas.restore();
        }
    }

    private void drawWinter(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(72, 207, 229, 242));
        path.reset();
        path.moveTo(w * .02f, h * .92f);
        path.lineTo(w * .27f, h * .44f);
        path.lineTo(w * .47f, h * .92f);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(w * .34f, h * .94f);
        path.lineTo(w * .59f, h * .35f);
        path.lineTo(w * .82f, h * .94f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(Color.argb(145, 229, 245, 255));
        for (int i = 0; i < 9; i++) {
            canvas.drawCircle(w * .18f + i * dp(18), dp(12) + (i % 4) * dp(16), dp(1.2f), paint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
