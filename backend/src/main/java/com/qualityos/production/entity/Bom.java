package com.qualityos.production.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A bill of materials or, for process manufacturing, a recipe: what goes into {@code baseQuantity} of the product. */
@Getter
@Entity
@Table(name = "boms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bom extends RevisionedDefinition {

    public enum Kind { BOM, RECIPE }

    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Setter
    private BigDecimal baseQuantity = BigDecimal.ONE;

    public Bom(Kind kind, UUID productId, int revision, String changeNote) {
        super(productId, revision, changeNote);
        this.kind = kind;
    }
}
