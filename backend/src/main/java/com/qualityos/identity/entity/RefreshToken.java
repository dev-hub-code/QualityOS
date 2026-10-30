package com.qualityos.identity.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Only the hash is stored. Tokens rotated from one login share a family, which is revoked as a unit. */
@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends AuditedEntity {

    private UUID userId;
    private UUID tenantId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;

    @Setter
    private Instant consumedAt;

    private Instant revokedAt;

    public RefreshToken(UUID userId, UUID tenantId, UUID familyId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
