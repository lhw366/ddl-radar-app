package com.ddlradar.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReminderBannerThemeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context ctx = instrumentation.getTargetContext();
    private static final int BANNER_ID = 28001;

    @Test public void everyMonthUsesItsNorthernHemisphereSeason() {
        String[] expected = {"冬日", "冬日", "春日", "春日", "春日", "夏日",
                "夏日", "夏日", "秋日", "秋日", "秋日", "冬日"};
        for (int month = 0; month < 12; month++) {
            SeasonTheme theme = SeasonTheme.forMonth(month);
            assertEquals("month " + (month + 1), expected[month], theme.name);
            assertFalse(theme.kicker.isEmpty());
            assertFalse(theme.mark.isEmpty());
            assertNotEquals(theme.topColor, theme.bottomColor);
        }
    }

    @Test public void currentSeasonalBannerRendersOnTheDesktop() throws Exception {
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(400);
        ReminderBanner.show(ctx, BANNER_ID, "高数作业今晚截止",
                "还有 5 分钟 · 记得提交 PDF", false);
        long end = System.currentTimeMillis() + 3000;
        while (ReminderBanner.visibleCount() == 0 && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals(1, ReminderBanner.visibleCount());
        instrumentation.waitForIdleSync();
        Thread.sleep(300);
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "seasonal-banner.png"))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        screen.recycle();
        assertFalse(MainActivity.visible);
        assertEquals(1, ReminderBanner.visibleCount());
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }

    @After public void cleanup() {
        ReminderBanner.dismiss(BANNER_ID);
    }
}
