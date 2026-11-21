package com.qualityos.organization.service;

import static com.qualityos.organization.dto.StateHolidayDtos.ALL_INDIA;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import com.qualityos.organization.dto.StateHolidayDtos.InheritedHoliday;
import com.qualityos.organization.dto.StateHolidayDtos.SaveStateHolidayRequest;
import com.qualityos.organization.dto.StateHolidayDtos.StateHolidayResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holidays an organisation keeps for a state, or for all of India. A facility takes those of the state its address
 * names, and All India's, unless it marks one as a day it works.
 */
@Service
public class StateHolidayService {

    private final JdbcTemplate jdbc;
    private final IndiaPlaces places;
    private final AuditService audit;

    StateHolidayService(JdbcTemplate jdbc, IndiaPlaces places, AuditService audit) {
        this.jdbc = jdbc;
        this.places = places;
        this.audit = audit;
    }

    /** A region's holidays in a year, by date; every region's when none is given. */
    @Transactional(readOnly = true)
    public List<StateHolidayResponse> list(String region, int year) {
        if (region != null) {
            requireRegion(region);
        }
        return jdbc.query("""
                SELECT id, region, holiday_on, name FROM state_holidays
                WHERE holiday_on BETWEEN ? AND ? AND (?::text IS NULL OR region = ?)
                ORDER BY holiday_on, region
                """, (rows, row) -> response(rows.getObject("id", UUID.class), rows.getString("region"),
                rows.getObject("holiday_on", LocalDate.class), rows.getString("name")),
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), region, region);
    }

    @Transactional
    public StateHolidayResponse add(SaveStateHolidayRequest request) {
        requireRegion(request.region());
        String name = request.name().trim();
        UUID id = UuidV7.generate();
        int added = jdbc.update("""
                INSERT INTO state_holidays (id, tenant_id, region, holiday_on, name, created_by)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, id, TenantContext.getCurrentTenantId(), request.region(), request.date(), name,
                TenantContext.getCurrentUserId());
        if (added == 0) {
            throw clash(request.region(), request.date());
        }
        StateHolidayResponse created = response(id, request.region(), request.date(), name);
        audit.record("STATE_HOLIDAY_ADDED", "StateHoliday", id, null, state(created));
        return created;
    }

    /** Changes a holiday's date or name; its region stays. */
    @Transactional
    public StateHolidayResponse update(UUID id, SaveStateHolidayRequest request) {
        StateHolidayResponse before = load(id);
        if (!before.region().equals(request.region())) {
            throw new BusinessRuleException("A holiday stays in its state. Remove it and add it to the other one.");
        }
        StateHolidayResponse after = response(id, before.region(), request.date(), request.name().trim());
        if (after.equals(before)) {
            return after;
        }
        Integer clashes = jdbc.queryForObject("""
                SELECT count(*) FROM state_holidays WHERE region = ? AND holiday_on = ? AND id <> ?
                """, Integer.class, after.region(), after.date(), id);
        if (clashes != null && clashes > 0) {
            throw clash(after.region(), after.date());
        }
        jdbc.update("UPDATE state_holidays SET holiday_on = ?, name = ? WHERE id = ?", after.date(), after.name(), id);
        audit.record("STATE_HOLIDAY_UPDATED", "StateHoliday", id, state(before), state(after));
        return after;
    }

    /** Also forgets which facilities worked on it. */
    @Transactional
    public void remove(UUID id) {
        StateHolidayResponse removed = load(id);
        jdbc.update("DELETE FROM state_holidays WHERE id = ?", id);
        audit.record("STATE_HOLIDAY_REMOVED", "StateHoliday", id, state(removed), null);
    }

    /** Marks a holiday of the facility's state, or of All India, as one the facility works on, or closes on again. */
    @Transactional
    public void setWorking(UUID facilityId, String facilityState, UUID holidayId, boolean working) {
        StateHolidayResponse holiday = load(holidayId);
        if (!regionsOf(facilityState).contains(holiday.region())) {
            throw new BusinessRuleException("This holiday is not one of the facility's state.");
        }
        int changed = working
                ? jdbc.update("""
                        INSERT INTO facility_working_holidays (tenant_id, facility_id, state_holiday_id, created_by)
                        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, TenantContext.getCurrentTenantId(), facilityId, holidayId,
                        TenantContext.getCurrentUserId())
                : jdbc.update("DELETE FROM facility_working_holidays WHERE facility_id = ? AND state_holiday_id = ?",
                        facilityId, holidayId);
        if (changed > 0) {
            audit.record(working ? "STATE_HOLIDAY_WORKED" : "STATE_HOLIDAY_CLOSED", "Facility", facilityId, null,
                    Map.of("date", holiday.date().toString(), "name", holiday.name(), "region", holiday.region()));
        }
    }

    /**
     * The state and All India holidays each facility takes between the two days, both included, with those it works
     * on marked. A facility whose state is not in the list takes All India's only.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<InheritedHoliday>> inherited(Collection<UUID> facilityIds, LocalDate from, LocalDate to) {
        Map<UUID, List<InheritedHoliday>> found = new HashMap<>();
        if (facilityIds.isEmpty()) {
            return found;
        }
        Map<UUID, Set<String>> regions = new HashMap<>();
        jdbc.query("SELECT id, state FROM facilities WHERE id = ANY (?)", rows -> {
            regions.put(rows.getObject("id", UUID.class), regionsOf(rows.getString("state")));
        }, (Object) facilityIds.toArray(UUID[]::new));
        Set<String> wanted = new HashSet<>();
        regions.values().forEach(wanted::addAll);
        List<StateHolidayResponse> holidays = jdbc.query("""
                SELECT id, region, holiday_on, name FROM state_holidays
                WHERE region = ANY (?) AND holiday_on BETWEEN ? AND ? ORDER BY holiday_on, region
                """, (rows, row) -> response(rows.getObject("id", UUID.class), rows.getString("region"),
                rows.getObject("holiday_on", LocalDate.class), rows.getString("name")),
                wanted.toArray(String[]::new), from, to);
        Set<String> worked = new HashSet<>();
        if (!holidays.isEmpty()) {
            jdbc.query("""
                    SELECT facility_id, state_holiday_id FROM facility_working_holidays
                    WHERE facility_id = ANY (?) AND state_holiday_id = ANY (?)
                    """, rows -> {
                worked.add(rows.getObject("facility_id", UUID.class) + "/"
                        + rows.getObject("state_holiday_id", UUID.class));
            }, facilityIds.toArray(UUID[]::new), holidays.stream().map(StateHolidayResponse::id).toArray(UUID[]::new));
        }
        regions.forEach((facility, theirs) -> {
            List<InheritedHoliday> taken = new ArrayList<>();
            for (StateHolidayResponse holiday : holidays) {
                if (theirs.contains(holiday.region())) {
                    taken.add(new InheritedHoliday(holiday.id(), holiday.region(), holiday.regionName(),
                            holiday.date(), holiday.name(), worked.contains(facility + "/" + holiday.id())));
                }
            }
            found.put(facility, taken);
        });
        return found;
    }

    /** All India, and the state an address names when it is in the list. */
    Set<String> regionsOf(String state) {
        Set<String> regions = new HashSet<>(Set.of(ALL_INDIA));
        places.codeOf(state).ifPresent(regions::add);
        return regions;
    }

    private StateHolidayResponse load(UUID id) {
        List<StateHolidayResponse> found = jdbc.query("SELECT id, region, holiday_on, name FROM state_holidays "
                + "WHERE id = ?", (rows, row) -> response(rows.getObject("id", UUID.class), rows.getString("region"),
                rows.getObject("holiday_on", LocalDate.class), rows.getString("name")), id);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("State holiday", id);
        }
        return found.getFirst();
    }

    private void requireRegion(String region) {
        if (!ALL_INDIA.equals(region) && places.nameOf(region).isEmpty()) {
            throw new BusinessRuleException("Choose a state, or All India.");
        }
    }

    private StateHolidayResponse response(UUID id, String region, LocalDate date, String name) {
        return new StateHolidayResponse(id, region, regionName(region), date, name);
    }

    private String regionName(String region) {
        return ALL_INDIA.equals(region) ? "All India" : places.nameOf(region).orElse(region);
    }

    private ConflictException clash(String region, LocalDate date) {
        return new ConflictException("%s is already a holiday in %s.".formatted(date, regionName(region)));
    }

    private static Map<String, Object> state(StateHolidayResponse holiday) {
        return Map.of("region", holiday.region(), "date", holiday.date().toString(), "name", holiday.name());
    }
}
