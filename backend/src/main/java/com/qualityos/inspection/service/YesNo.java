package com.qualityos.inspection.service;

import java.util.Locale;

/**
 * The expected answer of a yes/no check. Templates keep it as {@code true} or {@code false}; older ones may say "Yes"
 * or "No", which a plain {@link Boolean#parseBoolean} read as "No", failing every good answer.
 */
final class YesNo {

    private YesNo() {}

    /** @return the answer, or null for blank or anything that is not a yes or a no */
    static Boolean parse(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y" -> Boolean.TRUE;
            case "false", "no", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    static String label(String text) {
        Boolean answer = parse(text);
        return answer == null ? null : answer ? "Yes" : "No";
    }
}
