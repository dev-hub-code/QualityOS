package com.qualityos.quality.service;

import com.qualityos.common.exception.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where a process incident happened: a production line, a work centre, a machine, or several of them, all at one plant.
 * Read straight from their tables, under the tenant's row-level security, so that quality does not depend on
 * manufacturing's services.
 */
@Component
public class NcrPlaces {

    /** @param facilityId the plant they are all at; @param label for people, such as "Line L1 · Machine M-3" */
    public record Place(UUID facilityId, String label) {
    }

    private record Found(UUID facilityId, String label, UUID workCenterId) {
    }

    /** One place that can be chosen, for the form. @param workCenterId a machine's work centre, else null */
    public record Option(UUID id, String label, UUID facilityId, UUID workCenterId) {
    }

    /** What can be chosen, in the caller's plants. */
    public record Options(List<Option> lines, List<Option> workCenters, List<Option> machines) {
    }

    private final JdbcTemplate jdbc;

    NcrPlaces(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** For a new incident: each place must be in use, and they must agree with each other. */
    Place require(UUID lineId, UUID centreId, UUID machineId) {
        if (lineId == null && centreId == null && machineId == null) {
            throw new BusinessRuleException("Say where the incident happened: a production line, work centre or machine.");
        }
        Found line = lineId == null ? null : find("""
                SELECT facility_id, 'Line ' || code || ' – ' || name, NULL::uuid FROM production_lines
                WHERE id = ? AND archived_at IS NULL
                """, lineId, "This production line does not exist.");
        Found centre = centreId == null ? null : find("""
                SELECT facility_id, 'Work centre ' || code || ' – ' || name, NULL::uuid FROM work_centers
                WHERE id = ? AND archived_at IS NULL
                """, centreId, "This work centre does not exist.");
        Found machine = machineId == null ? null : find("""
                SELECT c.facility_id, 'Machine ' || m.code || ' – ' || m.name, m.work_center_id
                FROM machines m JOIN work_centers c ON c.tenant_id = m.tenant_id AND c.id = m.work_center_id
                WHERE m.id = ? AND m.status <> 'INACTIVE'
                """, machineId, "This machine does not exist.");
        if (machine != null && centreId != null && !centreId.equals(machine.workCenterId())) {
            throw new BusinessRuleException("This machine is not at that work centre.");
        }
        List<Found> found = new ArrayList<>();
        for (Found place : new Found[] {line, centre, machine}) {
            if (place != null) {
                found.add(place);
            }
        }
        UUID facility = found.getFirst().facilityId();
        if (found.stream().anyMatch(place -> !facility.equals(place.facilityId()))) {
            throw new BusinessRuleException("The line, work centre and machine must be at the same plant.");
        }
        return new Place(facility, String.join(" · ", found.stream().map(Found::label).toList()));
    }

    Options options(java.util.function.Predicate<UUID> allowedFacility) {
        java.util.function.Function<String, List<Option>> read = sql -> jdbc.query(sql, (rows, row) -> new Option(
                        rows.getObject(1, UUID.class), rows.getString(2), rows.getObject(3, UUID.class),
                        rows.getObject(4, UUID.class))).stream()
                .filter(option -> allowedFacility.test(option.facilityId()))
                .toList();
        return new Options(
                read.apply("""
                        SELECT id, code || ' – ' || name, facility_id, NULL::uuid FROM production_lines
                        WHERE archived_at IS NULL ORDER BY code
                        """),
                read.apply("""
                        SELECT id, code || ' – ' || name, facility_id, NULL::uuid FROM work_centers
                        WHERE archived_at IS NULL ORDER BY code
                        """),
                read.apply("""
                        SELECT m.id, m.code || ' – ' || m.name, c.facility_id, m.work_center_id
                        FROM machines m JOIN work_centers c ON c.tenant_id = m.tenant_id AND c.id = m.work_center_id
                        WHERE m.status <> 'INACTIVE' AND c.archived_at IS NULL ORDER BY m.code
                        """));
    }

    /** For an NCR already raised: archived places still show, by name. Null when it names none. */
    String label(UUID lineId, UUID centreId, UUID machineId) {
        List<String> labels = new ArrayList<>();
        if (lineId != null) {
            labels.addAll(jdbc.queryForList("SELECT 'Line ' || code || ' – ' || name FROM production_lines WHERE id = ?",
                    String.class, lineId));
        }
        if (centreId != null) {
            labels.addAll(jdbc.queryForList("SELECT 'Work centre ' || code || ' – ' || name FROM work_centers WHERE id = ?",
                    String.class, centreId));
        }
        if (machineId != null) {
            labels.addAll(jdbc.queryForList("SELECT 'Machine ' || code || ' – ' || name FROM machines WHERE id = ?",
                    String.class, machineId));
        }
        return labels.isEmpty() ? null : String.join(" · ", labels);
    }

    private Found find(String sql, UUID id, String missing) {
        return jdbc.query(sql, (rows, row) -> new Found(rows.getObject(1, UUID.class), rows.getString(2),
                rows.getObject(3, UUID.class)), id).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException(missing));
    }
}
