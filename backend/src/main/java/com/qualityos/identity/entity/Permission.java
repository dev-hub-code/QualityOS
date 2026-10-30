package com.qualityos.identity.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Seeded by migrations; read-only to the application. */
@Getter
@Entity
@Immutable
@Table(name = "permissions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {

    @Id
    private UUID id;

    private String code;
    private String module;
    private String description;
}
