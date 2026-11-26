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

    /** A report cell: an ISO day (kept so for spreadsheets) reads as a day; anything else is left as it is. */
    public static String cell(String value) {
        if (value == null || !ISO_DAY.matcher(value).matches()) {
            return value;
        }
        return label(LocalDate.parse(value));
    }

    private static final java.util.regex.Pattern ISO_DAY = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
}
