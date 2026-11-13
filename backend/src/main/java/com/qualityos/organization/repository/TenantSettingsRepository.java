package com.qualityos.organization.repository;

import com.qualityos.organization.entity.TenantSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSettingsRepository extends JpaRepository<TenantSettings, UUID> {

    /** The tenant filter limits this to the current organisation, which has at most one row. */
    Optional<TenantSettings> findFirstBy();
}
