package com.qualityos.identity.repository;

import com.qualityos.identity.entity.AccountToken;
import com.qualityos.identity.entity.AccountToken.Purpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    Optional<AccountToken> findByTokenHashAndPurpose(String hash, Purpose purpose);

    boolean existsByUserIdAndPurposeAndCreatedAtAfter(UUID userId, Purpose purpose, Instant after);

    /** Returns 1 for exactly one caller, so a token cannot be spent twice by concurrent requests. */
    @Modifying
    @Query("""
            update AccountToken t set t.usedAt = :now
            where t.id = :id and t.usedAt is null and t.expiresAt > :now
            """)
    int consume(UUID id, Instant now);

    /** Only the newest link works, which also stops an old email in someone's inbox being used later. */
    @Modifying
    @Query("""
            update AccountToken t set t.usedAt = :now
            where t.userId = :userId and t.purpose = :purpose and t.usedAt is null
            """)
    int invalidateOutstanding(UUID userId, Purpose purpose, Instant now);

    @Modifying
    @Query("delete from AccountToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
