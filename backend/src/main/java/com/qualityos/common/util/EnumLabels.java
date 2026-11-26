package com.qualityos.common.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** A stored value such as CONDITIONAL_ACCEPTANCE as people read it: "Conditional acceptance". Acronyms stay. */
public final class EnumLabels {

    private static final Pattern ENUM = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
    private static final Set<String> ACRONYMS = Set.of("IQC", "IPQC", "FQC", "OQC", "NCR", "CAPA", "OEE", "PO", "GRN");

    private EnumLabels() {
    }

    /** @return the value in words, or the value unchanged when it is not an enum name */
    public static String label(String value) {
        if (value == null || !ENUM.matcher(value).matches() || ACRONYMS.contains(value)) {
            return value;
        }
        String words = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
