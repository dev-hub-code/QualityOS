package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class TemplateSelectionIT extends TraceabilityFixture {

    private static final String APPLICABLE = "/api/v1/inspections/applicable-templates?";

    private String codeOf(String template) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))), "$.data.template.code");
    }

    private ResultActions inspectWithoutTemplate(String subjectKey, String subject) throws Exception {
        return as(owner, postJson("/api/v1/inspections", Map.of(subjectKey, subject)));
    }

    private Lot pendingLot(String material) throws Exception {
        return lot(material, "10");
    }

    @Test
    void aLotGetsItsMaterialsOwnTemplateBeforeAGeneralOne() throws Exception {
        String material = material(owner, true);
        String general = template("IQC");
        String own = template("IQC", Map.of("materialId", material));
        String elsewhere = template("IQC", Map.of("materialId", material(owner, true)));
        Lot lot = pendingLot(material);

        String fit = body(as(owner, get(APPLICABLE + "receiptItemId=" + lot.id())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(fit, "$.data.templates[*].id")).containsExactly(own, general);
        assertThat(JsonPath.<String>read(fit, "$.data.suggestedId")).isEqualTo(own);
        assertThat(elsewhere).isNotIn(JsonPath.<List<String>>read(fit, "$.data.templates[*].id"));

        inspectWithoutTemplate("receiptItemId", lot.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.specification.templateCode").value(codeOf(own)));
    }

    @Test
    void aLotWithOnlyOneGeneralTemplateGetsIt() throws Exception {
        String general = template("IQC");
        template("VISUAL");
        Lot lot = pendingLot(material(owner, true));

        inspectWithoutTemplate("receiptItemId", lot.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inspectionType").value("IQC"))
                .andExpect(jsonPath("$.data.specification.templateCode").value(codeOf(general)));
    }

    @Test
    void severalOrNoFittingTemplatesMustBeChosenByHand() throws Exception {
        Lot none = pendingLot(material(owner, true));
        inspectWithoutTemplate("receiptItemId", none.id())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("No published template fits this inspection. Publish one first."));

        String first = template("IQC");
        template("IQC");
        Lot lot = pendingLot(material(owner, true));
        as(owner, get(APPLICABLE + "receiptItemId=" + lot.id()))
                .andExpect(jsonPath("$.data.templates.length()").value(2))
                .andExpect(jsonPath("$.data.suggestedId").isEmpty());
        inspectWithoutTemplate("receiptItemId", lot.id())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("More than one template fits this inspection. Choose one."));
        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", lot.id(), "templateId", first)))
                .andExpect(status().isCreated());
    }

    @Test
    void anUnpublishedTemplateIsNeverOffered() throws Exception {
        as(owner, postJson("/api/v1/inspection-templates",
                        Map.of("code", code(), "name", "Draft only", "inspectionType", "IQC")))
                .andExpect(status().isCreated());
        Lot lot = pendingLot(material(owner, true));
        as(owner, get(APPLICABLE + "receiptItemId=" + lot.id())).andExpect(jsonPath("$.data.templates.length()").value(0));
    }

    @Test
    void aBatchGetsTheTemplateForItsStageAndItsProduct() throws Exception {
        String product = product();
        template("IPQC");
        String ownInProcess = template("IPQC", Map.of("productId", product));
        String ownFinal = template("FQC", Map.of("productId", product));
        template("FQC", Map.of("productId", product()));
        template("IQC");
        String batch = startBatch(product);

        as(owner, get(APPLICABLE + "productionBatchId=" + batch))
                .andExpect(jsonPath("$.data.templates[*].inspectionType")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("IPQC"))))
                .andExpect(jsonPath("$.data.suggestedId").value(ownInProcess));

        consumeLot(batch, releasedLot("10").id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        as(owner, get(APPLICABLE + "productionBatchId=" + batch))
                .andExpect(jsonPath("$.data.templates.length()").value(1))
                .andExpect(jsonPath("$.data.suggestedId").value(ownFinal));
        inspectWithoutTemplate("productionBatchId", batch)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inspectionType").value("FQC"))
                .andExpect(jsonPath("$.data.specification.templateCode").value(codeOf(ownFinal)));
    }

    @Test
    void aReleasedBatchHasNothingToSuggest() throws Exception {
        template("FQC");
        String batch = releasedBatch(releasedLot("10").id(), "5", "5");
        as(owner, get(APPLICABLE + "productionBatchId=" + batch))
                .andExpect(jsonPath("$.data.templates.length()").value(0))
                .andExpect(jsonPath("$.data.suggestedId").isEmpty());
    }

    @Test
    void theSubjectMustBeNamedOnce() throws Exception {
        as(owner, get(APPLICABLE)).andExpect(status().isUnprocessableContent());
    }
}
