package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.support.TransactionTemplate;

/** Boots the whole application against real Postgres, using the same two roles as deployments. */
class FoundationIT extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void flywayAppliedFoundationMigration() {
        assertThat(flyway.info().applied()).isNotEmpty().allMatch(migration -> migration.getState().isApplied());
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void runtimeConnectionIsUnprivilegedAndCannotBypassRowLevelSecurity() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("qualityos_app");
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
                Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE not_allowed (id int)"))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void tenantContextIsScopedToTheTransaction() {
        String tenantId = "7d1f7a52-8f4e-4d0a-9a55-1f6f3f2c0a11";
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        String insideTransaction = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            return jdbc.queryForObject("SELECT current_tenant_id()", String.class);
        });
        // Postgres leaves '' behind rather than unsetting, and the pool reuses this connection.
        String afterCommit = transaction.execute(status ->
                jdbc.queryForObject("SELECT current_tenant_id()", String.class));

        assertThat(insideTransaction).isEqualTo(tenantId);
        assertThat(afterCommit).isNull();
    }

    @Test
    void readinessIncludesTheDatabase() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void openApiDocumentDescribesTheApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("QualityOS API"))
                .andExpect(jsonPath("$.paths['/api/v1/system/info']").exists());
    }

    @Test
    void systemInfoUsesTheResponseEnvelope() throws Exception {
        mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.application").value("QualityOS"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
