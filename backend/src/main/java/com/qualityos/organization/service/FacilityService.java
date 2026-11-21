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
import com.qualityos.organization.dto.FacilityDtos.HolidayResponse;
import com.qualityos.organization.dto.FacilityDtos.SaveHolidayRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveWeeklyOffRequest;
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final StateHolidayService stateHolidays;

    FacilityService(
            FacilityRepository facilities,
            DepartmentRepository departments,
            ProductionLineRepository lines,
            ShiftRepository shifts,
            FacilityMapper mapper,
            PlanLimits limits,
            AuditService audit,
            List<ArchiveGuard> archiveGuards,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            StateHolidayService stateHolidays) {
        this.jdbc = jdbc;
        this.stateHolidays = stateHolidays;
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
        DaysOff offs = daysOff(id);
        java.time.LocalDate thisYear = java.time.LocalDate.now().withDayOfYear(1);
        return new FacilityDetail(
                mapper.toResponse(facility),
                departments.findByFacilityIdAndArchivedAtIsNullOrderByName(id).stream()
                        .<UnitResponse>map(mapper::toResponse).toList(),
                lines.findByFacilityIdAndArchivedAtIsNullOrderByName(id).stream()
                        .<UnitResponse>map(mapper::toResponse).toList(),
                shifts.findByFacilityIdAndArchivedAtIsNullOrderByStartsAt(id).stream()
                        .map(mapper::toResponse).toList(),
                jdbc.query("""
                        SELECT id, holiday_on, name FROM facility_holidays
                        WHERE facility_id = ? AND holiday_on >= ? ORDER BY holiday_on
                        """, (rows, row) -> new HolidayResponse(rows.getObject("id", UUID.class),
                        rows.getObject("holiday_on", java.time.LocalDate.class), rows.getString("name")),
                        id, thisYear),
                offs.weeklyOffDays(), offs.alternateOffDay(), offs.alternateOffWeeks(),
                stateHolidays.inherited(List.of(id), thisYear, thisYear.plusYears(2).minusDays(1))
                        .getOrDefault(id, List.of()));
    }

    /** Marks a holiday of the facility's state, or of All India, as a day it works, or closes on again. */
    @Transactional
    public void setStateHolidayWorking(UUID facilityId, UUID holidayId, boolean working) {
        Facility facility = load(facilityId);
        stateHolidays.setWorking(facilityId, facility.getState(), holidayId, working);
    }

    /** A facility's regular days off, as stored. */
    private record DaysOff(int weeklyOffDays, Integer alternateOffDay, int alternateOffWeeks) {

        Map<String, Object> state() {
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("weeklyOffDays", weeklyOffDays);
            state.put("alternateOffDay", alternateOffDay);
            state.put("alternateOffWeeks", alternateOffWeeks);
            return state;
        }
    }

    private DaysOff daysOff(UUID facilityId) {
        return jdbc.queryForObject(
                "SELECT weekly_off_days, alternate_off_day, alternate_off_weeks FROM facilities WHERE id = ?",
                (rows, row) -> new DaysOff(rows.getInt("weekly_off_days"),
                        rows.getObject("alternate_off_day", Integer.class), rows.getInt("alternate_off_weeks")),
                facilityId);
    }

    /**
     * Sets the days of the week the facility never works, such as Sunday, and a day it does not work in some weeks
     * of each month, such as the 2nd and 4th Saturday.
     */
    @Transactional
    public void setWeeklyOff(UUID facilityId, SaveWeeklyOffRequest request) {
        load(facilityId);
        Integer alternateDay = request.alternateDay();
        int alternateWeeks = request.alternateWeeks() == null ? 0 : request.alternateWeeks();
        if (alternateDay == null) {
            alternateWeeks = 0;
        } else if (alternateWeeks == 0) {
            throw new BusinessRuleException("Choose which weeks of the month the alternate day is off.");
        } else if (alternateWeeks == 31) {
            throw new BusinessRuleException("Off in every week of the month: choose it as a weekly off instead.");
        } else if ((request.daysOfWeek() & (1 << (alternateDay - 1))) != 0) {
            throw new BusinessRuleException("The alternate day is already off every week.");
        }
        DaysOff before = daysOff(facilityId);
        DaysOff after = new DaysOff(request.daysOfWeek(), alternateDay, alternateWeeks);
        if (before.equals(after)) {
            return;
        }
        jdbc.update("""
                UPDATE facilities SET weekly_off_days = ?, alternate_off_day = ?, alternate_off_weeks = ? WHERE id = ?
                """, after.weeklyOffDays(), after.alternateOffDay(), after.alternateOffWeeks(), facilityId);
        audit.record("WEEKLY_OFF_CHANGED", "Facility", facilityId, before.state(), after.state());
    }

    /** Whether a day is a regular day off: a weekly off, or the alternate day in one of its weeks of the month. */
    static boolean isDayOff(java.time.LocalDate day, int weeklyOffDays, Integer alternateOffDay,
            int alternateOffWeeks) {
        int weekday = day.getDayOfWeek().getValue();
        if ((weeklyOffDays & (1 << (weekday - 1))) != 0) {
            return true;
        }
        int occurrence = (day.getDayOfMonth() - 1) / 7;
        return alternateOffDay != null && alternateOffDay == weekday && (alternateOffWeeks & (1 << occurrence)) != 0;
    }

    @Transactional
    public HolidayResponse addHoliday(UUID facilityId, SaveHolidayRequest request) {
        load(facilityId);
        String name = request.name().trim();
        UUID id = com.qualityos.common.util.UuidV7.generate();
        int added = jdbc.update("""
                INSERT INTO facility_holidays (id, tenant_id, facility_id, holiday_on, name, created_by)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, id, com.qualityos.common.tenant.TenantContext.getCurrentTenantId(), facilityId, request.date(),
                name, com.qualityos.common.tenant.TenantContext.getCurrentUserId());
        if (added == 0) {
            throw new ConflictException("%s is already a holiday here.".formatted(request.date()));
        }
        audit.record("HOLIDAY_ADDED", "Facility", facilityId, null,
                Map.of("date", request.date().toString(), "name", name));
        return new HolidayResponse(id, request.date(), name);
    }

    /** Changes a holiday's date or name. */
    @Transactional
    public HolidayResponse updateHoliday(UUID facilityId, UUID holidayId, SaveHolidayRequest request) {
        load(facilityId);
        List<HolidayResponse> found = jdbc.query("""
                SELECT id, holiday_on, name FROM facility_holidays WHERE id = ? AND facility_id = ? FOR UPDATE
                """, (rows, row) -> new HolidayResponse(rows.getObject("id", UUID.class),
                rows.getObject("holiday_on", java.time.LocalDate.class), rows.getString("name")),
                holidayId, facilityId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Holiday", holidayId);
        }
        HolidayResponse before = found.getFirst();
        HolidayResponse after = new HolidayResponse(holidayId, request.date(), request.name().trim());
        if (before.equals(after)) {
            return after;
        }
        Integer clashes = jdbc.queryForObject("""
                SELECT count(*) FROM facility_holidays WHERE facility_id = ? AND holiday_on = ? AND id <> ?
                """, Integer.class, facilityId, after.date(), holidayId);
        if (clashes != null && clashes > 0) {
            throw new ConflictException("%s is already a holiday here.".formatted(after.date()));
        }
        jdbc.update("UPDATE facility_holidays SET holiday_on = ?, name = ? WHERE id = ?", after.date(), after.name(),
                holidayId);
        audit.record("HOLIDAY_UPDATED", "Facility", facilityId,
                Map.of("date", before.date().toString(), "name", before.name()),
                Map.of("date", after.date().toString(), "name", after.name()));
        return after;
    }

    @Transactional
    public void removeHoliday(UUID facilityId, UUID holidayId) {
        load(facilityId);
        List<java.time.LocalDate> removed = jdbc.queryForList(
                "DELETE FROM facility_holidays WHERE id = ? AND facility_id = ? RETURNING holiday_on",
                java.time.LocalDate.class, holidayId, facilityId);
        if (removed.isEmpty()) {
            throw new ResourceNotFoundException("Holiday", holidayId);
        }
        audit.record("HOLIDAY_REMOVED", "Facility", facilityId, Map.of("date", removed.getFirst().toString()), null);
    }

    /**
     * Each facility's holidays between the two days, both included: its own, its state's and All India's that it does
     * not work on, and its weekly and alternate days off.
     */
    @Transactional(readOnly = true)
    public Map<UUID, java.util.Set<java.time.LocalDate>> holidaysOf(Collection<UUID> facilityIds,
            java.time.LocalDate from, java.time.LocalDate to) {
        Map<UUID, java.util.Set<java.time.LocalDate>> found = new java.util.HashMap<>();
        if (facilityIds.isEmpty()) {
            return found;
        }
        jdbc.query("""
                SELECT id, weekly_off_days, alternate_off_day, alternate_off_weeks FROM facilities
                WHERE id = ANY (?) AND (weekly_off_days <> 0 OR alternate_off_day IS NOT NULL)
                """, rows -> {
            int weekly = rows.getInt("weekly_off_days");
            Integer alternateDay = rows.getObject("alternate_off_day", Integer.class);
            int alternateWeeks = rows.getInt("alternate_off_weeks");
            java.util.Set<java.time.LocalDate> days = found.computeIfAbsent(rows.getObject("id", UUID.class),
                    ignored -> new java.util.HashSet<>());
            for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (isDayOff(day, weekly, alternateDay, alternateWeeks)) {
                    days.add(day);
                }
            }
        }, (Object) facilityIds.toArray(UUID[]::new));
        jdbc.query("""
                SELECT facility_id, holiday_on FROM facility_holidays
                WHERE facility_id = ANY (?) AND holiday_on BETWEEN ? AND ?
                """, rows -> {
            found.computeIfAbsent(rows.getObject("facility_id", UUID.class), ignored -> new java.util.HashSet<>())
                    .add(rows.getObject("holiday_on", java.time.LocalDate.class));
        }, facilityIds.toArray(UUID[]::new), from, to);
        stateHolidays.inherited(facilityIds, from, to).forEach((facility, holidays) -> holidays.stream()
                .filter(holiday -> !holiday.working())
                .forEach(holiday -> found.computeIfAbsent(facility, ignored -> new java.util.HashSet<>())
                        .add(holiday.date())));
        return found;
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
        var created = mapper.toResponse(facility);
        audit.record("FACILITY_CREATED", "Facility", facility.getId(), null, audit.state(created));
        return created;
    }

    @Transactional
    public FacilityResponse update(UUID id, SaveFacilityRequest request) {
        Facility facility = load(id);
        var before = audit.state(mapper.toResponse(facility));
        facility.requireVersion(request.version());
        String code = request.code().trim();
        if (facilities.existsByCodeAndIdNot(code, id)) {
            throw new ConflictException("A facility with this code already exists.");
        }
        facility.setCode(code);
        apply(facility, request);
        facilities.saveAndFlush(facility);
        audit.recordChange("FACILITY_UPDATED", "Facility", id, before, audit.state(mapper.toResponse(facility)));
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
                        shift.getEndsAt(), shift.getBreakMinutes(), shift.getDaysOfWeek(), shift.getId()))
                .toList();
    }

    /** For other modules that must not fail when a facility has been archived. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID id) {
        return facilities.findById(id).map(facility -> facility.getArchivedAt() == null).orElse(false);
    }

    /** Facility names by id, for other modules' views. */
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> ids) {
        return facilities.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Facility::getId, Facility::getName));
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
