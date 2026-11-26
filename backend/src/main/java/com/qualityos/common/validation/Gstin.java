package com.qualityos.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An Indian GSTIN: a two-digit state code, the holder's PAN, an entity number, Z, and a check character. Blank passes,
 * since the number is optional wherever it is asked for.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = GstinValidator.class)
public @interface Gstin {

    String message() default "is not a valid GSTIN: check the 15 characters, the last one is a check character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
