package com.qualityos.product.mapper;

import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.product.dto.ProductDtos.ProductResponse;
import com.qualityos.product.dto.ProductDtos.RevisionResponse;
import com.qualityos.product.entity.Product;
import com.qualityos.product.entity.ProductRevision;
import com.qualityos.product.entity.ProductSpecification;
import org.mapstruct.Mapper;

@Mapper
public interface ProductMapper {

    ProductResponse toResponse(Product product);

    RevisionResponse toResponse(ProductRevision revision);

    SpecificationResponse toResponse(ProductSpecification specification);
}
