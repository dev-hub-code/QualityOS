package com.qualityos.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class FacilityDtos {

    private FacilityDtos() {
    }

    public record SaveFacilityRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = "^$|^[0-9]{2}[A-Z0-9]{13}$", message = "must be a 15 character GSTIN") String gstin,
            Long version) {
    }

    public record FacilityResponse(
            UUID id,
            String code,
            String name,
            String address,
            String city,
            String state,
            String postalCode,
            String gstin,
            long version) {
    }

    public record FacilityDetail(
            FacilityResponse facility,
            List<UnitResponse> departments,
            List<UnitResponse> productionLines,
            List<ShiftResponse> shifts) {
    }

    public record SaveUnitRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 200) String name) {
    }

    public record UnitResponse(UUID id, String code, String name) {
    }

    public record SaveShiftRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt) {
    }

    public record ShiftResponse(UUID id, String name, LocalTime startsAt, LocalTime endsAt) {
    }
}
