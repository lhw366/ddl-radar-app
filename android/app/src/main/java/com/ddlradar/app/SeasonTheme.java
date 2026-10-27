package com.ddlradar.app;

import android.graphics.Color;

/** Four restrained scenic palettes for the native cross-app reminder card. */
final class SeasonTheme {
    final String name;
    final String kicker;
    final String mark;
    final int topColor;
    final int bottomColor;
    final int accentColor;
    final int detailColor;

    private SeasonTheme(String name, String kicker, String mark, int topColor,
                        int bottomColor, int accentColor, int detailColor) {
        this.name = name;
        this.kicker = kicker;
        this.mark = mark;
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.accentColor = accentColor;
        this.detailColor = detailColor;
    }

    static SeasonTheme forMonth(int calendarMonth) {
        int month = calendarMonth + 1;
        if (month >= 3 && month <= 5) {
            return new SeasonTheme("春日", "SPRING · DDL", "🌸",
                    Color.argb(238, 27, 54, 43), Color.argb(244, 66, 116, 87),
                    Color.rgb(172, 230, 184), Color.rgb(213, 230, 216));
        }
        if (month >= 6 && month <= 8) {
            return new SeasonTheme("夏日", "SUMMER · DDL", "☀",
                    Color.argb(238, 20, 52, 68), Color.argb(244, 30, 105, 126),
                    Color.rgb(255, 215, 121), Color.rgb(208, 231, 232));
        }
        if (month >= 9 && month <= 11) {
            return new SeasonTheme("秋日", "AUTUMN · DDL", "🍂",
                    Color.argb(238, 48, 38, 28), Color.argb(244, 126, 80, 37),
                    Color.rgb(255, 197, 111), Color.rgb(236, 220, 196));
        }
        return new SeasonTheme("冬日", "WINTER · DDL", "❄",
                Color.argb(238, 29, 44, 62), Color.argb(244, 73, 100, 126),
                Color.rgb(190, 226, 250), Color.rgb(218, 230, 240));
    }
}
