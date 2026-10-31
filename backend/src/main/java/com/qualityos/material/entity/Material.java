package com.qualityos.material.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "materials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Material extends TenantOwnedEntity {

    private String code;
    private String name;
    private String description;
    private String grade;
    private UUID categoryId;
    private UUID uomId;
    private boolean inspectionRequired = true;
    private Instant archivedAt;

    /** Tenant-defined attributes with no fixed schema. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> properties = new HashMap<>();

    public Material(String code) {
        this.code = code;
    }
}
