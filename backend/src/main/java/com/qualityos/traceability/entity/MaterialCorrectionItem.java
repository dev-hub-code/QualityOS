package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One changed field: its value before and after, with readable labels. Append-only. */
@Getter
@Entity
@Table(name = "material_correction_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialCorrectionItem extends TenantOwnedEntity {

    public enum Field { MATERIAL, SUPPLIER, SUPPLIER_BATCH, QUANTITY, SOURCE_LOT }

    private UUID correctionId;

    @Enumerated(EnumType.STRING)
    private Field fieldName;

    private String originalValue;
    private String correctedValue;
    private String originalLabel;
    private String correctedLabel;

    public MaterialCorrectionItem(
            UUID correctionId,
            Field fieldName,
            String originalValue,
            String correctedValue,
            String originalLabel,
            String correctedLabel) {
        this.correctionId = correctionId;
        this.fieldName = fieldName;
        this.originalValue = originalValue;
        this.correctedValue = correctedValue;
        this.originalLabel = originalLabel;
        this.correctedLabel = correctedLabel;
    }
}
