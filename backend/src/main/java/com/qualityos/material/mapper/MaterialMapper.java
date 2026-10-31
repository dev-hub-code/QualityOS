package com.qualityos.material.mapper;

import com.qualityos.material.dto.MaterialDtos.CategoryResponse;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.dto.MaterialDtos.MaterialResponse;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.entity.Material;
import com.qualityos.material.entity.MaterialSpecification;
import com.qualityos.material.entity.ProductCategory;
import com.qualityos.material.entity.UnitOfMeasure;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface MaterialMapper {

    MaterialResponse toResponse(Material material);

    MaterialReference toReference(Material material);

    SpecificationResponse toResponse(MaterialSpecification specification);

    CategoryResponse toResponse(ProductCategory category);

    @Mapping(target = "system", expression = "java(unit.getTenantId() == null)")
    UnitResponse toResponse(UnitOfMeasure unit);
}
