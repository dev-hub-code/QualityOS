package com.qualityos.organization.service;

import com.qualityos.common.archive.ArchiveGuard;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.organization.dto.FacilityDtos.DepartmentOption;
import com.qualityos.organization.dto.FacilityDtos.FacilityDetail;
import com.qualityos.organization.dto.FacilityDtos.FacilityResponse;
import com.qualityos.organization.dto.FacilityDtos.SaveFacilityRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveShiftRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveUnitRequest;
import com.qualityos.organization.dto.FacilityDtos.ShiftCalendar;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final List<ArchiveGuard> archiveGuards;
    private final PlanLimits limits;

    FacilityService(
            FacilityRepository facilities,
            DepartmentRepository departments,
            ProductionLineRepository lines,
            ShiftRepository shifts,
            FacilityMapper mapper,
            PlanLimits limits,
            AuditService audit,
            List<ArchiveGuard> archiveGuards) {
        this.limits = limits;
        this.facilities = facilities;
        this.departments = departments;
        this.lines = lines;
        this.shifts = shifts;
        this.mapper = mapper;
        this.audit = audit;
        this.archiveGuards = archiveGuards;
    }

    @Transactional(readOnly = true)
    public PageResponse<FacilityResponse> list(String search, boolean archived, Pageable pageable) {
        Specification<Facility> filter = Specification.<Facility>where(Searches.archived(archived))
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
        Facility facility = load(id);
        ArchiveGuard.check(archiveGuards, ArchiveGuard.Kind.FACILITY, id, "facility");
        facility.setArchivedAt(Instant.now());
        audit.record("FACILITY_ARCHIVED", "Facility", id);
    }

    /** Brings an archived facility back into lists and pickers. */
    @Transactional
    public void restore(UUID id) {
        var restored = load(id);
        if (restored.getArchivedAt() == null) {
            throw new BusinessRuleException("This facility is not archived.");
        }
        restored.setArchivedAt(null);
        audit.record("FACILITY_RESTORED", "Facility", id);
    }

    @Transactional
    public UnitResponse addDepartment(UUID facilityId, SaveUnitRequest request) {
        load(facilityId);
        String code = request.code().trim();
        if (departments.existsByFacilityIdAndCode(facilityId, code)) {
            throw new ConflictException("This facility already has a department with this code.");
        }
        Department department = departments.save(new Department(facilityId, code, request.name().trim()));
        audit.record("DEPARTMENT_ADDED", "Facility", facilityId, null, Map.of("department", code));
        return mapper.toResponse(department);
    }

    @Transactional
    public UnitResponse addProductionLine(UUID facilityId, SaveUnitRequest request) {
        load(facilityId);
        String code = request.code().trim();
        if (lines.existsByFacilityIdAndCode(facilityId, code)) {
            throw new ConflictException("This facility already has a production line with this code.");
        }
        ProductionLine line = lines.save(new ProductionLine(facilityId, code, request.name().trim()));
        audit.record("PRODUCTION_LINE_ADDED", "Facility", facilityId, null, Map.of("productionLine", code));
        return mapper.toResponse(line);
    }

    @Transactional
    public ShiftResponse addShift(UUID facilityId, SaveShiftRequest request) {
        load(facilityId);
        String name = request.name().trim();
        if (shifts.existsByFacilityIdAndName(facilityId, name)) {
            throw new ConflictException("This facility already has a shift with this name.");
        }
        Shift shift = shifts.save(new Shift(facilityId, name, request.startsAt(), request.endsAt(),
                request.breakMinutes() == null ? 0 : request.breakMinutes(),
                request.daysOfWeek() == null ? Shift.EVERY_DAY : request.daysOfWeek()));
        audit.record("SHIFT_ADDED", "Facility", facilityId, null, Map.of("shift", name,
                "startsAt", String.valueOf(request.startsAt()), "endsAt", String.valueOf(request.endsAt())));
        return mapper.toResponse(shift);
    }

    @Transactional
    public void archiveDepartment(UUID facilityId, UUID id) {
        Department department = departments.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Department", id));
        department.setArchivedAt(Instant.now());
        audit.record("DEPARTMENT_ARCHIVED", "Facility", facilityId, Map.of("department", department.getCode()), null);
    }

    @Transactional
    public void archiveProductionLine(UUID facilityId, UUID id) {
        ProductionLine line = lines.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Production line", id));
        line.setArchivedAt(Instant.now());
        audit.record("PRODUCTION_LINE_ARCHIVED", "Facility", facilityId, Map.of("productionLine", line.getCode()), null);
    }

    @Transactional
    public void archiveShift(UUID facilityId, UUID id) {
        Shift shift = shifts.findById(id)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));
        shift.setArchivedAt(Instant.now());
        audit.record("SHIFT_ARCHIVED", "Facility", facilityId, Map.of("shift", shift.getName()), null);
    }

    /** Every facility of the organisation, archived ones included, for reports over history. */
    @Transactional(readOnly = true)
    public List<UUID> allIds() {
        return facilities.findAll().stream().map(Facility::getId).toList();
    }

    /** The active shifts of these facilities, for capacity and availability. */
    @Transactional(readOnly = true)
    public List<ShiftCalendar> shiftsOf(Collection<UUID> facilityIds) {
        return shifts.findAll().stream()
                .filter(shift -> shift.getArchivedAt() == null && facilityIds.contains(shift.getFacilityId()))
                .map(shift -> new ShiftCalendar(shift.getFacilityId(), shift.getName(), shift.getStartsAt(),
                        shift.getEndsAt(), shift.getBreakMinutes(), shift.getDaysOfWeek()))
                .toList();
    }

    /** For other modules that must not fail when a facility has been archived. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID id) {
        return facilities.findById(id).map(facility -> facility.getArchivedAt() == null).orElse(false);
    }

    /** For other modules recording new activity at a facility. */
    @Transactional(readOnly = true)
    public void requireActive(UUID id) {
        if (load(id).getArchivedAt() != null) {
            throw new BusinessRuleException("This facility is archived.");
        }
    }

    /** For other modules placing work on a line: it must be active and belong to the facility. */
    @Transactional(readOnly = true)
    public void requireActiveLine(UUID facilityId, UUID lineId) {
        ProductionLine line = lines.findById(lineId)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new BusinessRuleException("This production line is not part of the facility."));
        if (line.getArchivedAt() != null) {
            throw new BusinessRuleException("This production line is archived.");
        }
    }

    /** The active departments of these facilities, each named with its facility, for other modules' pickers. */
    @Transactional(readOnly = true)
    public List<DepartmentOption> departmentsOf(Collection<UUID> facilityIds) {
        if (facilityIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> facilityCodes = new HashMap<>();
        facilities.findAllById(facilityIds).forEach(facility -> facilityCodes.put(facility.getId(), facility.getCode()));
        return departments.findByFacilityIdInAndArchivedAtIsNullOrderByName(facilityIds).stream()
                .map(department -> new DepartmentOption(department.getId(), department.getFacilityId(),
                        facilityCodes.get(department.getFacilityId()), department.getCode(), department.getName()))
                .sorted(java.util.Comparator.comparing(DepartmentOption::facilityCode, java.util.Comparator.nullsLast(
                        java.util.Comparator.naturalOrder())).thenComparing(DepartmentOption::name))
                .toList();
    }

    /**
     * For other modules naming a department: it must be active and belong to the facility.
     *
     * @return the department's name, to keep on the record as it was when chosen
     */
    @Transactional(readOnly = true)
    public String requireDepartment(UUID facilityId, UUID departmentId) {
        Department department = departments.findById(departmentId)
                .filter(found -> found.getFacilityId().equals(facilityId))
                .orElseThrow(() -> new BusinessRuleException("This department is not part of the facility."));
        if (department.getArchivedAt() != null) {
            throw new BusinessRuleException("This department is archived.");
        }
        return department.getName();
    }

    /** Codes of facilities and production lines, by id, for other modules to show. */
    @Transactional(readOnly = true)
    public Map<UUID, String> codes(Collection<UUID> ids) {
        Map<UUID, String> codes = new HashMap<>();
        facilities.findAllById(ids).forEach(facility -> codes.put(facility.getId(), facility.getCode()));
        lines.findAllById(ids).forEach(line -> codes.put(line.getId(), line.getCode()));
        return codes;
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
