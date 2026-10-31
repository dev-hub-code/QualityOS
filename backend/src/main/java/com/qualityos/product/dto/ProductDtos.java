package com.qualityos.product.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record SaveProductRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 20) String productType,
            UUID categoryId,
            @NotNull UUID uomId,
            Long version) {
    }

    public record ProductResponse(
            UUID id,
            String code,
            String name,
            String description,
            String productType,
            UUID categoryId,
            UUID uomId,
            String currentRevision,
            long version) {
    }

    public record ProductDetail(
            ProductResponse product,
            List<RevisionResponse> revisions,
            List<SpecificationResponse> specifications,
            List<BillOfMaterialResponse> materials) {
    }

    public record AddRevisionRequest(
            @NotBlank @Size(max = 20) String revision,
            @Size(max = 1000) String changeNote,
            @NotNull LocalDate effectiveFrom) {
    }

    public record RevisionResponse(UUID id, String revision, String changeNote, LocalDate effectiveFrom) {
    }

    public record AddMaterialRequest(
            @NotNull UUID materialId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantityPerUnit,
            @NotNull UUID uomId) {
    }

    public record BillOfMaterialResponse(
            UUID id,
            UUID materialId,
            String materialCode,
            String materialName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantityPerUnit,
            UUID uomId) {
    }

    public record ProductReference(UUID id, String code, String name, UUID uomId) {
    }
}
