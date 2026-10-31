package com.qualityos.material.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MaterialDtos {

    private MaterialDtos() {
    }

    public record SaveMaterialRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @Size(max = 100) String grade,
            UUID categoryId,
            @NotNull UUID uomId,
            boolean inspectionRequired,
            Map<String, String> properties,
            Long version) {
    }

    public record MaterialResponse(
            UUID id,
            String code,
            String name,
            String description,
            String grade,
            UUID categoryId,
            UUID uomId,
            boolean inspectionRequired,
            Map<String, String> properties,
            long version) {
    }

    public record MaterialDetail(MaterialResponse material, List<SpecificationResponse> specifications) {
    }

    /** Shared by material and product specifications. */
    public record SaveSpecificationRequest(
            @NotBlank @Size(max = 200) String name,
            @Digits(integer = 12, fraction = 6) BigDecimal nominalValue,
            @Digits(integer = 12, fraction = 6) BigDecimal minValue,
            @Digits(integer = 12, fraction = 6) BigDecimal maxValue,
            @Size(max = 500) String textValue,
            UUID uomId,
            @Size(max = 1000) String notes) {
    }

    public record SpecificationResponse(
            UUID id,
            String name,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal nominalValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxValue,
            String textValue,
            UUID uomId,
            String notes) {
    }

    public record SaveUnitRequest(
            @NotBlank @Size(max = 10) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String dimension) {
    }

    public record UnitResponse(UUID id, String code, String name, String dimension, boolean system) {
    }

    public record SaveCategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CategoryResponse(UUID id, String name) {
    }

    /** What other modules may know about a material. */
    public record MaterialReference(UUID id, String code, String name, UUID uomId, boolean inspectionRequired) {
    }
}
