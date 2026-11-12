package com.qualityos.material.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Not a {@code TenantOwnedEntity}: shared defaults have no tenant, so reads use explicit queries. */
@Getter
@Entity
@Table(name = "units_of_measure")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitOfMeasure extends AuditedEntity {

    private UUID tenantId;
    private String code;
    private String name;
    private String dimension;
    /** How many of the dimension's base unit one of this holds. Null when it converts only to itself. */
    private BigDecimal toBase;

    public UnitOfMeasure(UUID tenantId, String code, String name, String dimension, BigDecimal toBase) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.dimension = dimension;
        this.toBase = toBase;
    }

    /**
     * The same amount in another unit, to the six decimal places quantities are kept to.
     *
     * @return empty when the two units measure different things, or either has no factor
     */
    public Optional<BigDecimal> convert(BigDecimal quantity, UnitOfMeasure target) {
        if (getId().equals(target.getId())) {
            return Optional.of(quantity);
        }
        if (toBase == null || target.toBase == null || !dimension.equalsIgnoreCase(target.dimension)) {
            return Optional.empty();
        }
        return Optional.of(quantity.multiply(toBase).divide(target.toBase, 6, RoundingMode.HALF_UP));
    }
}
