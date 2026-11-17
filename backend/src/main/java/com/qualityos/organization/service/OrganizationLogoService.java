package com.qualityos.organization.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The current organisation's logo. Only PNG and JPEG are taken, recognised by their content rather than the name or
 * the type the browser claims, because an SVG or HTML file could carry script.
 */
@Service
public class OrganizationLogoService {

    /** Small enough to travel with every email. */
    public static final int MAX_BYTES = 256 * 1024;

    /** @param updatedAt also serves as the version a browser may cache */
    public record Logo(String contentType, byte[] data, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    OrganizationLogoService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Optional<Logo> current() {
        List<Logo> found = jdbc.query("SELECT content_type, data, updated_at FROM tenant_logos", (rows, row) ->
                new Logo(rows.getString("content_type"), rows.getBytes("data"),
                        rows.getTimestamp("updated_at").toInstant()));
        return found.stream().findFirst();
    }

    @Transactional
    public Logo save(MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (data.length == 0) {
            throw new BusinessRuleException("Choose an image to upload.");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessRuleException("The logo must be 256 KB or smaller.");
        }
        String type = typeOf(data);
        UUID tenant = TenantContext.getCurrentTenantId();
        jdbc.update("""
                INSERT INTO tenant_logos (tenant_id, content_type, data, updated_at, updated_by)
                VALUES (?, ?, ?, now(), ?)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET content_type = excluded.content_type, data = excluded.data, updated_at = now(),
                        updated_by = excluded.updated_by
                """, tenant, type, data, TenantContext.getCurrentUserId());
        audit.record("ORGANIZATION_LOGO_UPDATED", "Organization", tenant, null,
                Map.of("contentType", type, "bytes", data.length));
        return current().orElseThrow();
    }

    @Transactional
    public void remove() {
        if (jdbc.update("DELETE FROM tenant_logos") > 0) {
            audit.record("ORGANIZATION_LOGO_REMOVED", "Organization", TenantContext.getCurrentTenantId());
        }
    }

    static String typeOf(byte[] data) {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "image/png";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new BusinessRuleException("The logo must be a PNG or JPEG image.");
    }
}
