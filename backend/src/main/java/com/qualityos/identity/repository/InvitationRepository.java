package com.qualityos.identity.repository;

import com.qualityos.identity.entity.Invitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :hash")
    Optional<Invitation> findByHashForUpdate(String hash);

    Optional<Invitation> findByEmailAndStatus(String email, Invitation.Status status);

    List<Invitation> findByStatusOrderByCreatedAtDesc(Invitation.Status status);
}
