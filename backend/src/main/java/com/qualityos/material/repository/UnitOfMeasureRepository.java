package com.qualityos.material.repository;

import com.qualityos.material.entity.UnitOfMeasure;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID> {

    @Query("select u from UnitOfMeasure u where u.tenantId is null or u.tenantId = :tenantId order by u.dimension, u.code")
    List<UnitOfMeasure> findVisible(UUID tenantId);

    @Query("""
            select count(u) > 0 from UnitOfMeasure u
            where u.code = :code and (u.tenantId is null or u.tenantId = :tenantId)
            """)
    boolean existsVisibleByCode(String code, UUID tenantId);
}
