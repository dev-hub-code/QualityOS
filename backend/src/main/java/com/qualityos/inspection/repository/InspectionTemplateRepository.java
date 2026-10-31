package com.qualityos.inspection.repository;

import com.qualityos.inspection.entity.InspectionTemplate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InspectionTemplateRepository
        extends JpaRepository<InspectionTemplate, UUID>, JpaSpecificationExecutor<InspectionTemplate> {

    boolean existsByCode(String code);
}
