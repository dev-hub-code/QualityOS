package com.qualityos.inspection.repository;

import com.qualityos.inspection.entity.TemplateVersion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, UUID> {

    @EntityGraph(attributePaths = "parameters")
    List<TemplateVersion> findByTemplateIdOrderByVersionNumberDesc(UUID templateId);

    @EntityGraph(attributePaths = "parameters")
    @Query("select v from TemplateVersion v where v.id = :id")
    Optional<TemplateVersion> findWithParameters(UUID id);

    Optional<TemplateVersion> findByTemplateIdAndStatus(UUID templateId, TemplateVersion.Status status);

    /** The version in force on a date, which may have been superseded by one that takes effect later. */
    @EntityGraph(attributePaths = "parameters")
    @Query("""
            select v from TemplateVersion v
            where v.templateId = :templateId and v.status <> 'DRAFT' and v.effectiveFrom <= :date
              and (v.effectiveTo is null or v.effectiveTo > :date)
            order by v.versionNumber desc
            """)
    List<TemplateVersion> findInForce(UUID templateId, LocalDate date);
}
