package com.qualityos.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.config.CorrelationIdFilter;
import com.qualityos.common.security.TenantAccessResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandlerTest.TestController.class, GlobalExceptionHandlerTest.OpenSecurity.class})
class GlobalExceptionHandlerTest {

    /** The slice loads every Spring Converter, including the JWT one that needs this. */
    @MockitoBean
    private TenantAccessResolver tenantAccessResolver;

    @Autowired
    private MockMvc mvc;

    @Test
    void domainNotFound() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Supplier 42 was not found"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void businessRuleViolation() throws Exception {
        mvc.perform(get("/test/rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void bodyValidationReportsEachField() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.violations[0].field").value("name"));
    }

    @Test
    void parameterValidationReportsTheParameter() throws Exception {
        mvc.perform(get("/test/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.violations[0].field").value("size"));
    }

    @Test
    void wrongPathVariableTypeIsAValidationFailure() throws Exception {
        mvc.perform(get("/test/items/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.violations[0].field").value("id"));
    }

    @Test
    void unreadableBodyDoesNotEchoParserDetails() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The request body could not be read."));
    }

    @Test
    void unknownRouteUsesEnvelope() throws Exception {
        mvc.perform(get("/test/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void wrongMethodUsesEnvelope() throws Exception {
        mvc.perform(post("/test/rule"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeUsesEnvelope() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void optimisticLockFailureIsAConflict() throws Exception {
        mvc.perform(get("/test/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void dataIntegrityFailureHidesDatabaseDetails() throws Exception {
        mvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(content().string(not(containsString("uq_secret_constraint"))));
    }

    @Test
    void unexpectedFailureHidesInternalsAndCarriesCorrelationId() throws Exception {
        mvc.perform(get("/test/crash").header(CorrelationIdFilter.HEADER, "trace-12345678"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-12345678"))
                .andExpect(jsonPath("$.correlationId").value("trace-12345678"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("db-password"))));
    }

    @Test
    void methodSecurityDenialIsForbiddenNotAServerError() throws Exception {
        mvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @TestConfiguration
    static class OpenSecurity {

        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/test/denied")
        void denied() {
            throw new AccessDeniedException("no");
        }

        record Body(@NotBlank String name) {
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Supplier", 42);
        }

        @GetMapping("/test/rule")
        void rule() {
            throw new BusinessRuleException("A published template cannot be modified");
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/test/param")
        void param(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/test/items/{id}")
        void item(@PathVariable UUID id) {
        }

        @GetMapping("/test/stale")
        void stale() {
            throw new OptimisticLockingFailureException("row version mismatch");
        }

        @GetMapping("/test/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException("duplicate key violates uq_secret_constraint");
        }

        @GetMapping("/test/crash")
        void crash() {
            throw new IllegalStateException("connection failed for db-password");
        }
    }
}
