package com.qualityos.organization.mapper;

import com.qualityos.organization.dto.FacilityDtos.FacilityResponse;
import com.qualityos.organization.dto.FacilityDtos.ShiftResponse;
import com.qualityos.organization.dto.FacilityDtos.UnitResponse;
import com.qualityos.organization.entity.Facility;
import com.qualityos.organization.entity.FacilityUnit;
import com.qualityos.organization.entity.Shift;
import org.mapstruct.Mapper;

@Mapper
public interface FacilityMapper {

    FacilityResponse toResponse(Facility facility);

    UnitResponse toResponse(FacilityUnit unit);

    ShiftResponse toResponse(Shift shift);
}
