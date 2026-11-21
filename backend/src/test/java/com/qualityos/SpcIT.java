package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpcIT extends TraceabilityFixture {

    private static final String SPC = "/api/v1/spc";

    /** A diameter between 19.9 and 20.1 on three samples, and a hardness without limits. */
    private String shaftTemplate() throws Exception {
        String template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates",
                Map.of("code", code(), "name", "Shaft IQC", "inspectionType", "IQC")))
                .andExpect(status().isCreated())), "$.data.template.id");
        List<String> drafts = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> diameter = new java.util.HashMap<>(Map.of("code", "DIA", "name", "Diameter",
                "dataType", "NUMERIC", "criticality", "MAJOR", "mandatory", true, "perSample", true,
                "evidenceRequired", false, "minValue", "19.9", "maxValue", "20.1"));
        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + drafts.getFirst())
                .contentType("application/json").content(json.writeValueAsString(Map.of("samplingType", "FIXED",
                        "sampleSize", 3, "acceptanceNumber", 0, "parameters", List.of(diameter)))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish",
                Map.of("effectiveFrom", LocalDate.now().toString()))).andExpect(status().isOk());
        return template;
    }

    private static double num(String body, String path) {
        return JsonPath.<Number>read(body, path).doubleValue();
    }

    private void inspected(String template, String... diameters) throws Exception {
        IssuedOrder order = issuedOrder(owner, "100");
        String item = JsonPath.read(body(receive(owner, order, "100").andExpect(status().isCreated())),
                "$.data.items[0].id");
        String inspection = idOf(body(as(owner, postJson("/api/v1/inspections",
                Map.of("receiptItemId", item, "templateId", template))).andExpect(status().isCreated())));
        for (int sample = 1; sample <= diameters.length; sample++) {
            as(owner, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", List.of(
                    Map.of("parameterCode", "DIA", "sampleNumber", sample, "valueNumeric", diameters[sample - 1])))))
                    .andExpect(status().isOk());
        }
        as(owner, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of())).andExpect(status().isOk());
    }

    @Test
    void chartsAParameterFromTheInspectionsThatMeasuredIt() throws Exception {
        String template = shaftTemplate();
        inspected(template, "20.00", "20.02", "19.98");
        inspected(template, "20.01", "20.03", "20.02");
        inspected(template, "19.99", "20.00", "19.97");
        inspected(template, "20.02", "20.00", "20.04");

        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        as(inspector, get(SPC + "/characteristics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')].parameterName").value("Diameter"))
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')].measurements").value(12));

        // Three samples an inspection suit an X̄-R chart, judged against 19.9 to 20.1.
        String chart = body(as(inspector, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(chart, "$.data.chartType")).isEqualTo("XBAR_R");
        assertThat(JsonPath.<Integer>read(chart, "$.data.subgroupSize")).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(chart, "$.data.points")).hasSize(4);
        assertThat(num(chart, "$.data.lowerSpec")).isCloseTo(19.9, within(1e-9));
        assertThat(num(chart, "$.data.upperSpec")).isCloseTo(20.1, within(1e-9));
        // Means 20.00, 20.02, 19.9867, 20.02; ranges 0.04, 0.02, 0.03, 0.04.
        assertThat(num(chart, "$.data.valueLimits.centre")).isCloseTo(20.006667, within(1e-5));
        assertThat(num(chart, "$.data.rangeLimits.centre")).isCloseTo(0.0325, within(1e-9));
        double sigma = 0.0325 / 1.693;
        assertThat(num(chart, "$.data.capability.cp")).isCloseTo(0.2 / (6 * sigma), within(1e-6));
        assertThat(num(chart, "$.data.capability.cpk"))
                .isCloseTo((20.1 - 20.006667) / (3 * sigma), within(1e-4));
        assertThat(JsonPath.<Boolean>read(chart, "$.data.preliminary")).isTrue();

        // The individuals chart takes all twelve, in the order measured.
        String individuals = body(as(inspector, get(SPC + "/chart?templateId=" + template
                + "&parameterCode=DIA&chartType=I_MR")));
        assertThat(JsonPath.<List<Number>>read(individuals, "$.data.points[*].value")).hasSize(12)
                .extracting(Number::doubleValue).startsWith(20.0, 20.02, 19.98);
        assertThat(JsonPath.<Object>read(individuals, "$.data.points[0].range")).isNull();

        // Nothing outside the period, the parameter, the member's facilities or the organisation.
        String old = LocalDate.now().minusYears(1).toString();
        as(inspector, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&from=" + old + "&to=" + old))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("No measurements of DIA in this period."));
        as(inspector, get(SPC + "/chart?templateId=" + template + "&parameterCode=NONE"))
                .andExpect(status().isUnprocessableContent());
        as(inspector, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA&from="
                + LocalDate.now().minusYears(3) + "&to=" + LocalDate.now())).andExpect(status().isUnprocessableContent());
        limit(manager, facility(owner));
        as(manager, get(SPC + "/characteristics"))
                .andExpect(jsonPath("$.data[?(@.templateId == '" + template + "')]").isEmpty());
        as(manager, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA"))
                .andExpect(status().isUnprocessableContent());
        Session stranger = register();
        as(stranger, get(SPC + "/chart?templateId=" + template + "&parameterCode=DIA"))
                .andExpect(status().isNotFound());
        as(stranger, get(SPC + "/characteristics")).andExpect(jsonPath("$.data").isEmpty());
    }
}
