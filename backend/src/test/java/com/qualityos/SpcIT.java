package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

class SpcIT extends TraceabilityFixture {

    private static final String SPC = "/api/v1/spc";

    private static Map<String, Object> diameter(String min, String max) {
        return new HashMap<>(Map.of("code", "DIA", "name", "Diameter", "dataType", "NUMERIC", "criticality", "MAJOR",
                "mandatory", true, "perSample", true, "evidenceRequired", false, "minValue", min, "maxValue", max));
    }

    private static Map<String, Object> finish() {
        return new HashMap<>(Map.of("code", "FIN", "name", "Finish", "dataType", "TEXT", "criticality", "MINOR",
                "mandatory", false, "perSample", false, "evidenceRequired", false));
    }

    /** Saves the template's draft with these parameters, five samples, and publishes it. */
    private void publish(String template, Map<String, Object> diameter) throws Exception {
        List<String> drafts = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + drafts.getFirst())
                .contentType("application/json").content(json.writeValueAsString(Map.of("samplingType", "FIXED",
                        "sampleSize", 5, "acceptanceNumber", 0, "parameters", List.of(diameter, finish())))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish",
                Map.of("effectiveFrom", LocalDate.now().toString()))).andExpect(status().isOk());
    }

    private String shaftTemplate() throws Exception {
        String template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates",
                Map.of("code", code(), "name", "Shaft IQC", "inspectionType", "IQC")))
                .andExpect(status().isCreated())), "$.data.template.id");
        publish(template, diameter("19.9", "20.1"));
        return template;
    }

    private static double num(String body, String path) {
        return JsonPath.<Number>read(body, path).doubleValue();
    }

    /** An inspection of a new receipt with these diameters recorded, not yet submitted. */
    /** One order, received a little at a time, so every inspection is at the same facility. */
    private IssuedOrder order;

    private String recorded(String template, String... diameters) throws Exception {
        if (order == null) {
            order = issuedOrder(owner, "10000");
        }
        String item = JsonPath.read(body(receive(owner, order, "100").andExpect(status().isCreated())),
                "$.data.items[0].id");
        String inspection = idOf(body(as(owner, postJson("/api/v1/inspections",
                Map.of("receiptItemId", item, "templateId", template))).andExpect(status().isCreated())));
        List<Map<String, Object>> results = new ArrayList<>();
        for (int sample = 1; sample <= diameters.length; sample++) {
            results.add(Map.of("parameterCode", "DIA", "sampleNumber", sample, "valueNumeric", diameters[sample - 1]));
        }
        as(owner, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", results)))
                .andExpect(status().isOk());
        return inspection;
    }

    private String submitted(String template, String... diameters) throws Exception {
        String inspection = recorded(template, diameters);
        as(owner, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of())).andExpect(status().isOk());
        return inspection;
    }

    private String chart(Session member, String template, String extra) throws Exception {
        return body(as(member, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA" + extra))
                .andExpect(status().isOk()));
    }

    @Test
    void chartsSubmittedInspectionsInOrderAndJudgesTheirCapability() throws Exception {
        String template = shaftTemplate();
        List<String> inspections = new ArrayList<>();
        inspections.add(submitted(template, "20.00", "20.02", "19.98", "20.01", "19.99"));
        inspections.add(submitted(template, "20.01", "20.03", "20.02", "20.00", "20.04"));
        inspections.add(submitted(template, "19.99", "20.00", "19.97", "19.98", "20.01"));
        inspections.add(submitted(template, "20.02", "20.00", "20.04", "20.01", "20.03"));
        inspections.add(submitted(template, "20.00", "19.99", "20.01", "20.02", "19.98"));
        // Neither a pending nor a cancelled inspection is charted.
        recorded(template, "21.00", "21.00", "21.00", "21.00", "21.00");
        String cancelled = recorded(template, "19.00", "19.00", "19.00", "19.00", "19.00");
        // The app refuses to cancel one with results, so the status is set directly to prove the filter.
        asDatabaseSuperuser().update("UPDATE inspections SET status = 'CANCELLED', submitted_at = now() WHERE id = ?",
                UUID.fromString(cancelled));

        as(manager, get(SPC + "/parameters")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')].parameterCode").value("DIA"))
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')].parameterName").value("Diameter"))
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')].subgroups").value(5));

        String chart = chart(manager, template, "");
        assertThat(JsonPath.<String>read(chart, "$.data.chartType")).isEqualTo("XBAR_R");
        assertThat(JsonPath.<List<String>>read(chart, "$.data.points[*].inspectionId")).isEqualTo(inspections);
        assertThat(JsonPath.<List<Integer>>read(chart, "$.data.points[*].n")).containsOnly(5);
        assertThat(num(chart, "$.data.parameter.lsl")).isCloseTo(19.9, within(1e-9));
        assertThat(num(chart, "$.data.parameter.usl")).isCloseTo(20.1, within(1e-9));
        assertThat(JsonPath.<Boolean>read(chart, "$.data.parameter.specChanged")).isFalse();
        // Means 20.00, 20.02, 19.99, 20.02, 20.00; ranges 0.04, 0.04, 0.04, 0.04, 0.04.
        double sigma = 0.04 / 2.326;
        assertThat(num(chart, "$.data.centre")).isCloseTo(20.006, within(1e-9));
        assertThat(num(chart, "$.data.sigma")).isCloseTo(sigma, within(1e-6));
        assertThat(num(chart, "$.data.points[0].ucl")).isCloseTo(20.006 + 3 * sigma / Math.sqrt(5), within(1e-4));
        assertThat(num(chart, "$.data.capability.cp")).isCloseTo(0.2 / (6 * sigma), within(1e-4));
        assertThat(num(chart, "$.data.capability.cpk")).isCloseTo((20.1 - 20.006) / (3 * sigma), within(1e-4));
        assertThat(JsonPath.<List<String>>read(chart, "$.data.warnings"))
                .anyMatch(warning -> warning.startsWith("Fewer than 25 subgroups"));
        assertThat(JsonPath.<Boolean>read(chart, "$.data.truncated")).isFalse();

        // The individuals chart takes all 25 readings, in submission and sample order.
        String individuals = chart(manager, template, "&chart=I_MR");
        assertThat(JsonPath.<String>read(individuals, "$.data.chartType")).isEqualTo("I_MR");
        assertThat(JsonPath.<List<Integer>>read(individuals, "$.data.points[*].sampleNumber")).hasSize(25)
                .startsWith(1, 2, 3, 4, 5, 1);
        assertThat(JsonPath.<Object>read(individuals, "$.data.points[0].range")).isNull();

        // Narrowed to the inspections' material every reading stays; to another, none is left.
        String material = asDatabaseSuperuser().queryForObject("SELECT material_id::text FROM inspections WHERE id = ?",
                String.class, UUID.fromString(inspections.getFirst()));
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&chart=I_MR&materialId=" + material))
                .andExpect(jsonPath("$.data.points.length()").value(25));
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&materialId=" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aNewSpecificationIsFlaggedAndItsLimitsUsed() throws Exception {
        String template = shaftTemplate();
        submitted(template, "20.00", "20.02", "19.98", "20.01", "19.99");
        submitted(template, "20.01", "20.03", "20.02", "20.00", "20.04");
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/versions", Map.of()))
                .andExpect(status().isCreated());
        publish(template, diameter("19.95", "20.05"));
        submitted(template, "19.99", "20.00", "19.97", "19.98", "20.01");

        String chart = chart(manager, template, "");
        assertThat(JsonPath.<Boolean>read(chart, "$.data.parameter.specChanged")).isTrue();
        assertThat(num(chart, "$.data.parameter.lsl")).isCloseTo(19.95, within(1e-9));
        assertThat(num(chart, "$.data.parameter.usl")).isCloseTo(20.05, within(1e-9));
        assertThat(JsonPath.<List<Object>>read(chart, "$.data.points")).hasSize(3);
        assertThat(JsonPath.<List<String>>read(chart, "$.data.warnings"))
                .anyMatch(warning -> warning.contains("specification changed"));
    }

    @Test
    void refusesWhatCannotBeChartedAndHidesWhatTheMemberMayNotSee() throws Exception {
        String template = shaftTemplate();
        submitted(template, "20.00", "20.02", "19.98", "20.01", "19.99");

        // One subgroup is too few for X̄-R; the message says the minimum.
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&chart=XBAR_R"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message", Matchers.containsString("at least 2 inspections")));
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=FIN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message", Matchers.containsString("only numeric parameters")));
        as(manager, get(SPC + "/chart?templateId=" + template))
                .andExpect(status().isBadRequest());
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&materialId=" + UUID.randomUUID()
                + "&productId=" + UUID.randomUUID())).andExpect(status().isBadRequest());
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&from="
                + LocalDate.now().minusYears(3) + "&to=" + LocalDate.now())).andExpect(status().isUnprocessableContent());

        // A member limited to another facility sees none of its points, and a facility beyond their scope is unknown.
        String elsewhere = facility(owner);
        limit(manager, elsewhere);
        as(manager, get(SPC + "/parameters"))
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')]").isEmpty());
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&chart=I_MR"))
                .andExpect(status().isUnprocessableContent());
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&facilityId=" + facility(owner)))
                .andExpect(status().isNotFound());

        Session stranger = register();
        as(stranger, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA")).andExpect(status().isNotFound());
        as(stranger, get(SPC + "/parameters")).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void needsReportViewAndInspectionViewAndAPlanWithReports() throws Exception {
        String template = shaftTemplate();
        // A Quality Inspector has no report view; a Production Planner has no inspection view.
        for (String role : List.of("QUALITY_INSPECTOR", "PRODUCTION_PLANNER")) {
            Session member = addMember(owner, role);
            as(member, get(SPC + "/parameters")).andExpect(status().isForbidden());
            as(member, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA"))
                    .andExpect(status().isForbidden());
        }

        asDatabaseSuperuser().update("UPDATE subscriptions SET plan_id = (SELECT id FROM subscription_plans "
                + "WHERE code = 'STARTER') WHERE tenant_id = ?", owner.tenantId());
        as(owner, get("/api/v1/auth/me"))
                .andExpect(jsonPath("$.data.permissions", Matchers.not(Matchers.hasItem("REPORT_VIEW"))));
        as(owner, get(SPC + "/parameters")).andExpect(status().isForbidden());
        as(owner, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA")).andExpect(status().isForbidden());
    }
}
