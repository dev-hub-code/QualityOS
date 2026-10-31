package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.Subscription;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** Row-level security leaves only the current tenant's row. */
    @Query("select s from Subscription s")
    Optional<Subscription> findCurrent();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s")
    Optional<Subscription> lockCurrent();
}
