package com.qualityos.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record SaveSupplierRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @Pattern(regexp = "^$|^[0-9]{2}[A-Z0-9]{13}$", message = "must be a 15 character GSTIN") String gstin,
            UUID categoryId,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            Long version) {
    }

    public record SupplierResponse(
            UUID id,
            String code,
            String name,
            String legalName,
            String gstin,
            UUID categoryId,
            String email,
            String phone,
            String address,
            String city,
            String state,
            String postalCode,
            String countryCode,
            String status,
            String statusNote,
            Instant approvedAt,
            long version) {
    }

    public record SupplierDetail(SupplierResponse supplier, List<ContactResponse> contacts) {
    }

    public record StatusNoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record SaveContactRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String designation,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            boolean primary) {
    }

    public record ContactResponse(
            UUID id, String name, String designation, String email, String phone, boolean primary) {
    }

    public record SaveCategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CategoryResponse(UUID id, String name) {
    }

    /** What other modules may know about a supplier. */
    public record SupplierReference(UUID id, String code, String name) {
    }
}
