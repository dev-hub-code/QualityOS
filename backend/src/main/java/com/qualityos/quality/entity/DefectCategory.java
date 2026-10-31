package com.qualityos.quality.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "defect_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DefectCategory extends TenantOwnedEntity {

    private String code;

    @Setter
    private String name;

    @Setter
    private Instant archivedAt;

    public DefectCategory(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
