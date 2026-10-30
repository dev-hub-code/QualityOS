package com.qualityos.organization.mapper;

import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.entity.Tenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface OrganizationMapper {

    @Mapping(target = "onboarded", expression = "java(tenant.getOnboardedAt() != null)")
    OrganizationResponse toResponse(Tenant tenant);
}
