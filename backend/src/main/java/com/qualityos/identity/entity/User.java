package com.qualityos.identity.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A person. Global, not tenant-owned: one user can belong to several organisations. */
@Getter
@Setter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AuditedEntity {

    public enum Status { ACTIVE, DISABLED }

    private String email;
    private String passwordHash;
    private String fullName;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant emailVerifiedAt;

    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }
}
