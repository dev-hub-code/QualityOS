package com.qualityos.product.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends TenantOwnedEntity {

    public enum Type { FINISHED, SEMI_FINISHED }

    private String code;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private Type productType;

    private UUID categoryId;
    private UUID uomId;
    private String currentRevision = "A";
    private Instant archivedAt;

    public Product(String code) {
        this.code = code;
    }
}
