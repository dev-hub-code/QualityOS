package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.FeatureDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureDefinitionRepository extends JpaRepository<FeatureDefinition, UUID> {

    List<FeatureDefinition> findAllByOrderByName();

    Optional<FeatureDefinition> findByCode(String code);
}
