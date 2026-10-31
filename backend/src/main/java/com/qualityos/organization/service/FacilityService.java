package com.qualityos.organization.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.organization.dto.FacilityDtos.FacilityDetail;
import com.qualityos.organization.dto.FacilityDtos.FacilityResponse;
import com.qualityos.organization.dto.FacilityDtos.SaveFacilityRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveShiftRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveUnitRequest;
import com.qualityos.organization.dto.FacilityDtos.ShiftResponse;
import com.qualityos.organization.dto.FacilityDtos.UnitResponse;
import com.qualityos.organization.entity.Department;
import com.qualityos.organization.entity.Facility;
import com.qualityos.organization.entity.ProductionLine;
import com.qualityos.organization.entity.Shift;
import com.qualityos.organization.mapper.FacilityMapper;
import com.qualityos.organization.repository.DepartmentRepository;
import com.qualityos.organization.repository.FacilityRepository;
import com.qualityos.organization.repository.ProductionLineRepository;
import com.qualityos.organization.repository.ShiftRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.qualityos.subscription.service.PlanLimits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FacilityService {

    private final FacilityRepository facilities;
    private final DepartmentRepository departments;
    private final ProductionLineRepository lines;
    private final ShiftRepository shifts;
    private final FacilityMapper mapper;
    private final AuditService audit;
    private final PlanLimits limits;

    FacilityService(
            FacilityRepository facilities,
            DepartmentRepository departments,
            ProductionLineRepository lines,
            ShiftRepository shifts,
            FacilityMapper mapper,
            PlanLimits limits,
            AuditService audit) {
        this.limits = limits;
        this.facilities = facilities;
        this.departments = departments;
        this.lines = lines;
        this.shifts = shifts;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<FacilityResponse> list(String search, Pageable pageable) {
        Specification<Facility> filter = Specification.<Facility>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name", "city"));
        return PageResponse.from(facilities.findAll(filter, pageable), mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public FacilityDetail get(UUID id) {
        Facility facility = load(id);
        return new FacilityDetail(
                mapper.toResponse(facility),
                departments.findByFacilityIdAndArchivedAtIsNullOrderByName(id).stream()
                        .<UnitResponse>map(mapper::toResponse).toList(),
                lines.findByFacilityIdAndArchivedAtIsNullOrderByName(id).stream()
                        .<UnitResponse>map(mapper::toResponse).toList(),
                shifts.findByFacilityIdAndArchivedAtIsNullOrderByStartsAt(id).stream()
                        .map(mapper::toResponse).toList());
    }

    @Transactional
    public FacilityResponse create(SaveFacilityRequest request) {
        String code = request.code().trim();
        if (facilities.existsByCode(code)) {
            throw new ConflictException("A facility with this code already exists.");
        }
        limits.requireFacilityPlace();
        Facility facility = new Facility(code);
        apply(facility, request);
        facilities.saveAndFlush(facility);
        audit.record("FACILITY_CREATED", "Facility", facility.getId());
        return mapper.toResponse(facility);
    }

    @Transactional
    public FacilityResponse update(UUID id, SaveFacilityRequest request) {
        Facility facility = load(id);
        facility.requireVersion(request.version());
        apply(facility, request);
        facilities.saveAndFlush(facility);
        audit.record("FACILITY_UPDATED", "Facility", id);
        return mapper.toResponse(facility);
    }

    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("FACILITY_ARCHIVED", "Facility", id);
    }

    @Transactional
    public UnitResponse addDepartment(UUID facilityId, SaveUnitRequest request) {
        load(facilityId);
        String code = request.code().trim();
        if (departments.existsByFacilityIdAndCode(facilityId, code)) {
            throw new ConflictException("This facility already has a department with this code.");
        }
        return mapper.toResponse(departments.save(new Department(facilityId, code, request.name().trim())));
    }

    @Transactional
    public UnitResponse addProductionLine(UUID facilityId, SaveUnitRequest request) {
        load(facilityId);
        String code = request.code().trim();
        if (lines.existsByFacilityIdAndCode(facilityId, code)) {
            throw new ConflictException("This facility already has a production line with this code.");
        }
        return mapper.toResponse(lines.save(new ProductionLine(facilityId, code, request.name().trim())));
    }

    @Transactional
    public ShiftResponse addShift(UUID facilityId, SaveShiftRequest request) {
        load(facilityId);
        String name = request.name().trim();
        if (shifts.existsByFacilityIdAndName(facilityId, name)) {
            throw new ConflictException("This facility already has a shift with this name.");
        }
        return mapper.toResponse(shifts.save(new Shift(facilityId, name, request.startsAt(), request.endsAt())));
    }

    @Transactional
    public void archiveDepartment(UUID facilityId, UUID id) {
        Department department = departments.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Department", id));
        department.setArchivedAt(Instant.now());
    }

    @Transactional
    public void archiveProductionLine(UUID facilityId, UUID id) {
        ProductionLine line = lines.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Production line", id));
        line.setArchivedAt(Instant.now());
    }

    @Transactional
    public void archiveShift(UUID facilityId, UUID id) {
        Shift shift = shifts.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));
        shift.setArchivedAt(Instant.now());
    }

    /** For other modules recording new activity at a facility. */
    @Transactional(readOnly = true)
    public void requireActive(UUID id) {
        if (load(id).getArchivedAt() != null) {
            throw new BusinessRuleException("This facility is archived.");
        }
    }

    private Facility load(UUID id) {
        return facilities.findById(id).orElseThrow(() -> new ResourceNotFoundException("Facility", id));
    }

    private static void apply(Facility facility, SaveFacilityRequest request) {
        facility.setName(request.name().trim());
        facility.setAddress(request.address());
        facility.setCity(request.city());
        facility.setState(request.state());
        facility.setPostalCode(request.postalCode());
        facility.setGstin(StringUtils.hasText(request.gstin()) ? request.gstin() : null);
    }
}
