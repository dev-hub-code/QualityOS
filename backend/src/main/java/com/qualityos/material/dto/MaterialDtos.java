package com.qualityos.material.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
            Long version,
            /* Days a lot keeps from receipt; none means lots have no expiry unless one is entered. */
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(36500) Integer shelfLifeDays) {
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
            long version,
            Integer shelfLifeDays) {
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

    /**
     * A unit of the organisation's own. The plainest way to say how it converts is {@code equalsQuantity} of another
     * unit ("1 MG equals 0.001 GM"): its kind is then that unit's. Otherwise give the kind and how many of its base
     * unit one holds, or no factor for a reading that never converts, such as viscosity.
     */
    public record SaveUnitRequest(
            @NotBlank @Size(max = 10) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String dimension,
            @Positive @Digits(integer = 12, fraction = 12) BigDecimal toBase,
            @Positive @Digits(integer = 12, fraction = 12) BigDecimal equalsQuantity,
            UUID equalsUnitId) {

        public SaveUnitRequest(String code, String name, String dimension, BigDecimal toBase) {
            this(code, name, dimension, toBase, null, null);
        }
    }

    public record UnitResponse(
            UUID id,
            String code,
            String name,
            String dimension,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal toBase,
            boolean system) {
    }

    public record SaveCategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CategoryResponse(UUID id, String name) {
    }

    /** What other modules may know about a material. */
    public record MaterialReference(UUID id, String code, String name, UUID uomId, boolean inspectionRequired,
            Integer shelfLifeDays) {
    }

    /** "1 {uomId} = {factor} of the item's own unit", for one material or product. */
    public record AddConversionRequest(
            @NotNull UUID uomId,
            @NotNull @Positive @Digits(integer = 12, fraction = 12) BigDecimal factor) {
    }

    public record ConversionResponse(UUID id, UUID uomId, String unitCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factor) {
    }

    /**
     * An item's own unit, its conversions, and every unit a quantity of it may be entered in: its own, units of the
     * same kind, and those its conversions reach.
     */
    public record ItemUnits(UUID stockUnitId, List<ConversionResponse> conversions, List<UUID> enterableUnitIds) {
    }
}
