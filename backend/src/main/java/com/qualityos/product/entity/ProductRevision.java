package com.qualityos.product.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** History of a product's revisions. Rows are added, never changed. */
@Getter
@Entity
@Table(name = "product_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductRevision extends TenantOwnedEntity {

    private UUID productId;
    private String revision;
    private String changeNote;
    private LocalDate effectiveFrom;

    public ProductRevision(UUID productId, String revision, String changeNote, LocalDate effectiveFrom) {
        this.productId = productId;
        this.revision = revision;
        this.changeNote = changeNote;
        this.effectiveFrom = effectiveFrom;
    }
}
