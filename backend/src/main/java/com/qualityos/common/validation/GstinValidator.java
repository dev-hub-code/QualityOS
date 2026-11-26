package com.qualityos.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class GstinValidator implements ConstraintValidator<Gstin, String> {

    private static final Pattern SHAPE = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || valid(value);
    }

    /** The shape, then the check character: the GSTN's base-36 weighted sum over the first 14 characters. */
    public static boolean valid(String gstin) {
        if (!SHAPE.matcher(gstin).matches()) {
            return false;
        }
        return gstin.charAt(14) == checkCharacter(gstin.substring(0, 14));
    }

    static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < first14.length(); i++) {
            int product = CHARACTERS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARACTERS.charAt((36 - sum % 36) % 36);
    }
}
