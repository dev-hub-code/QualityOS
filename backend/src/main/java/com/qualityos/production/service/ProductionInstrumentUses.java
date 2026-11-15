package com.qualityos.production.service;

import com.qualityos.calibration.service.InstrumentUseSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells calibration which operations a shop-floor measurement took with an instrument, one entry per operation. Days are
 * in the organisation's time zone, as calibration dates are.
 */
@Component
class ProductionInstrumentUses implements InstrumentUseSource {

    private static final String USES = """
            SELECT w.work_order_number, o.sequence, o.id AS operation_id,
                   max((m.created_at AT TIME ZONE COALESCE(t.timezone, 'Asia/Kolkata'))::date) AS used_on
            FROM operation_measurements m
            JOIN work_orders w ON w.tenant_id = m.tenant_id AND w.id = m.work_order_id
            JOIN work_order_operations o ON o.tenant_id = m.tenant_id AND o.id = m.operation_id
            JOIN tenants t ON t.id = m.tenant_id
            WHERE m.instrument_id = ?
              AND (m.created_at AT TIME ZONE COALESCE(t.timezone, 'Asia/Kolkata'))::date BETWEEN ? AND ?
            GROUP BY w.work_order_number, o.sequence, o.id
            ORDER BY used_on DESC, w.work_order_number, o.sequence
            """;

    private final JdbcTemplate jdbc;

    ProductionInstrumentUses(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<InstrumentUse> usesBetween(UUID instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query(USES, (rs, row) -> new InstrumentUse(
                rs.getString("work_order_number") + " operation " + rs.getInt("sequence"),
                "/manufacturing/shop-floor/" + rs.getObject("operation_id", UUID.class),
                rs.getObject("used_on", LocalDate.class)), instrumentId, from, to);
    }
}
