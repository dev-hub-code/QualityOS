package com.qualityos.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A calendar day as people read it in a message: "26 Nov 2026", as the screens show it, not 2026-11-26. */
public final class DayLabels {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private DayLabels() {
    }

    public static String label(LocalDate day) {
        return day == null ? "" : day.format(DAY);
    }
}
