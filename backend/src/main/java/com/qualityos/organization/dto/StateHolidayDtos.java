package com.qualityos.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Holidays kept for a whole state, or for all of India, that every facility there takes. */
public final class StateHolidayDtos {

    /** The region of a holiday for every state. */
    public static final String ALL_INDIA = "ALL";

    private StateHolidayDtos() {
    }

    /** {@code region}: a state's two-digit GST code, such as 27, or ALL for all of India. */
    public record SaveStateHolidayRequest(
            @NotBlank @Pattern(regexp = "ALL|[0-9]{2}", message = "must be ALL or a state's GST code") String region,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 200) String name) {
    }

    /** {@code regionName}: the state's name, or All India. */
    public record StateHolidayResponse(UUID id, String region, String regionName, LocalDate date, String name) {
    }

    /** A state or All India holiday as one facility takes it; {@code working} when the facility works all the same. */
    public record InheritedHoliday(UUID id, String region, String regionName, LocalDate date, String name,
            boolean working) {
    }

    public record SetWorkingRequest(@NotNull Boolean working) {
    }
}
