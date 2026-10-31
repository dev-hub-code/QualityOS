package com.qualityos.supplier.mapper;

import com.qualityos.supplier.dto.SupplierDtos.CategoryResponse;
import com.qualityos.supplier.dto.SupplierDtos.ContactResponse;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.dto.SupplierDtos.SupplierResponse;
import com.qualityos.supplier.entity.Supplier;
import com.qualityos.supplier.entity.SupplierCategory;
import com.qualityos.supplier.entity.SupplierContact;
import org.mapstruct.Mapper;

@Mapper
public interface SupplierMapper {

    SupplierResponse toResponse(Supplier supplier);

    SupplierReference toReference(Supplier supplier);

    ContactResponse toResponse(SupplierContact contact);

    CategoryResponse toResponse(SupplierCategory category);
}
