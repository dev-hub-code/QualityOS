package com.qualityos.identity.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Only the hash of the token is stored. */
@Getter
@Entity
@Table(name = "invitations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation extends TenantOwnedEntity {

    public enum Status { PENDING, ACCEPTED, REVOKED }

    private String email;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<UUID> roleIds;

    private String tokenHash;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Instant expiresAt;
    private Instant acceptedAt;

    public Invitation(String email, List<UUID> roleIds, String tokenHash, Instant expiresAt) {
        this.email = email;
        this.roleIds = roleIds;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return status == Status.PENDING && expiresAt.isAfter(now);
    }

    public void renew(String newTokenHash, Instant newExpiry) {
        tokenHash = newTokenHash;
        expiresAt = newExpiry;
    }

    public void revoke() {
        status = Status.REVOKED;
    }

    public void accept(Instant now) {
        status = Status.ACCEPTED;
        acceptedAt = now;
    }
}
