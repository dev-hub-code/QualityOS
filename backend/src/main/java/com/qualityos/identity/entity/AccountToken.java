package com.qualityos.identity.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A one-time token sent by email. Only the hash is stored. */
@Getter
@Entity
@Table(name = "account_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountToken extends AuditedEntity {

    public enum Purpose { PASSWORD_RESET, EMAIL_VERIFICATION }

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;

    public AccountToken(UUID userId, Purpose purpose, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
