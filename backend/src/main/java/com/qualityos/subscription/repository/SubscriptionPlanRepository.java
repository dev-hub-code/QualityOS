package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.SubscriptionPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByCode(String code);

    List<SubscriptionPlan> findAllByOrderByDisplayOrder();

    List<SubscriptionPlan> findByActiveTrueOrderByDisplayOrder();
}
