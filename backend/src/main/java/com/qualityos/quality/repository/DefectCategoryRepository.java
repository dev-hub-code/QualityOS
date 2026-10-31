package com.qualityos.quality.repository;

import com.qualityos.quality.entity.DefectCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectCategoryRepository extends JpaRepository<DefectCategory, UUID> {

    List<DefectCategory> findByArchivedAtIsNullOrderByCode();

    boolean existsByCode(String code);
}
