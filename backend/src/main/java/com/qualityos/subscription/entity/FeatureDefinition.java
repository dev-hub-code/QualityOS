package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.AuditedEntity;
import com.qualityos.common.util.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Something a plan can include. What a person may do with it stays with their role. */
@Getter
@Setter
@Entity
@Table(name = "feature_definitions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureDefinition extends AuditedEntity {

    public enum ValueType { BOOLEAN, NUMBER, TEXT, STRUCTURED }

    private String code;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private ValueType valueType;

    private boolean active = true;
    private boolean defaultEnabled;

    public FeatureDefinition(String code, String name, ValueType valueType) {
        setId(UuidV7.generate());
        this.code = code;
        this.name = name;
        this.valueType = valueType;
        this.description = "";
    }
}
