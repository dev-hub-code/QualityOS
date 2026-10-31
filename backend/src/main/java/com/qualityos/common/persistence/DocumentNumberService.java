package com.qualityos.common.persistence;

import com.qualityos.common.tenant.TenantContext;
import java.time.Year;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sequential document numbers per tenant and year, such as {@code PO-2026-00001}. The increment holds a row lock
 * until the caller's transaction ends, so a rollback leaves no gap and concurrent callers cannot share a number.
 */
@Service
public class DocumentNumberService {

    private static final String NEXT = """
            INSERT INTO document_sequences (tenant_id, sequence_key, last_value) VALUES (?, ?, 1)
            ON CONFLICT (tenant_id, sequence_key)
            DO UPDATE SET last_value = document_sequences.last_value + 1
            RETURNING last_value
            """;

    private final JdbcTemplate jdbc;

    DocumentNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int year = Year.now().getValue();
        Long value = jdbc.queryForObject(NEXT, Long.class, tenantId, prefix + "-" + year);
        return "%s-%d-%05d".formatted(prefix, year, value);
    }
}
