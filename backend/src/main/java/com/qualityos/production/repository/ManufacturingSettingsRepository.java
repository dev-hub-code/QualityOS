package com.qualityos.production.repository;

import com.qualityos.production.entity.ManufacturingSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturingSettingsRepository extends JpaRepository<ManufacturingSettings, UUID> {

    /** Row-level security leaves only the current organisation's row. */
    Optional<ManufacturingSettings> findFirstBy();
}
