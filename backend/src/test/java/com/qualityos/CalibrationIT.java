package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.calibration.service.QrCodes;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalibrationIT extends TraceabilityFixture {

    private static final String INSTRUMENTS = "/api/v1/instruments";

    private Map<String, Object> request(String facility, String due) {
        Map<String, Object> body = new HashMap<>();
        body.put("instrumentCode", "VC-" + code());
        body.put("name", "Vernier caliper");
        body.put("category", "Dimensional");
        body.put("facilityId", facility);
        body.put("frequencyDays", 180);
        body.put("nextDueOn", due);
        return body;
    }

    private String instrument(String due) throws Exception {
        return instrument(facility(owner), due);
    }

    private String instrument(String facility, String due) throws Exception {
        return idOf(body(as(owner, postJson(INSTRUMENTS, request(facility, due))).andExpect(status().isCreated())));
    }

    private ResultActions calibrate(String instrument, String on, String result) throws Exception {
        return as(owner, postJson(INSTRUMENTS + "/" + instrument + "/calibrations", Map.of(
                "calibratedOn", on, "result", result, "performedBy", "NABL Lab", "certificateNumber", "C-1")));
    }

    private String inspection() throws Exception {
        String batch = startBatch(product());
        Lot lot = releasedLot("10");
        consumeLot(batch, lot.id(), "10").andExpect(status().isOk());
        complete(batch, "10").andExpect(status().isOk());
        return idOf(body(inspect("productionBatchId", batch, "FQC").andExpect(status().isCreated())));
    }

    private ResultActions use(String inspection, String instrument) throws Exception {
        return as(owner, postJson("/api/v1/inspections/" + inspection + "/instruments",
                Map.of("instrumentIds", List.of(instrument))));
    }

    /** Inspects and accepts the goods, measured with {@code instrument} when there is one. */
    private String accepted(String subjectKey, String subject, String type, String instrument) throws Exception {
        return pass(idOf(body(inspect(subjectKey, subject, type).andExpect(status().isCreated()))), instrument);
    }

    /** Records a good result on an inspection already started, and accepts the goods. */
    private String pass(String inspection, String instrument) throws Exception {
        String path = "/api/v1/inspections/" + inspection;
        if (instrument != null) {
            use(inspection, instrument).andExpect(status().isOk());
        }
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.0")))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", "ACCEPT"))).andExpect(status().isOk());
        return inspection;
    }

    private record Inspected(String batch, String inspection) {
    }

    private Inspected acceptedBatch(String instrument) throws Exception {
        String batch = startBatch(product());
        Lot lot = releasedLot("10");
        consumeLot(batch, lot.id(), "10").andExpect(status().isOk());
        complete(batch, "10").andExpect(status().isOk());
        return new Inspected(batch, accepted("productionBatchId", batch, "FQC", instrument));
    }

    private ResultActions decide(String instrument, String inspection, String decision) throws Exception {
        String path = INSTRUMENTS + "/" + instrument + "/impact-reviews";
        List<String> ids = JsonPath.read(body(as(owner, get(path))),
                "$.data[0].reviews[?(@.inspectionId == '" + inspection + "')].id");
        return as(owner, postJson(path + "/" + ids.getFirst() + "/decision",
                Map.of("decision", decision, "note", "Checked against the reference gauge")));
    }

    /** The inspection started on its own to check again what {@code earlier} released. */
    private String reinspectionOf(String earlier) throws Exception {
        String detail = body(as(owner, get("/api/v1/inspections/" + earlier)));
        String later = JsonPath.read(detail, "$.data.reinspectedBy.id");
        as(owner, get("/api/v1/inspections/" + later))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reinspectionOf.id").value(earlier));
        return later;
    }

    private static String days(int offset) {
        return LocalDate.now().plusDays(offset).toString();
    }

    @Test
    void aPassMovesTheDueDateByTheFrequency() throws Exception {
        String id = instrument(days(5));
        as(owner, get(INSTRUMENTS + "/" + id)).andExpect(jsonPath("$.data.dueState").value("DUE_SOON"));
        calibrate(id, days(0), "PASS").andExpect(status().isCreated());
        as(owner, get(INSTRUMENTS + "/" + id))
                .andExpect(jsonPath("$.data.nextDueOn").value(days(180)))
                .andExpect(jsonPath("$.data.lastCalibratedOn").value(days(0)))
                .andExpect(jsonPath("$.data.dueState").value("OK"));
    }

    @Test
    void aCalibrationCannotBeInTheFutureOrBeforeTheLastOne() throws Exception {
        String id = instrument(days(5));
        calibrate(id, days(1), "PASS").andExpect(status().isBadRequest());
        calibrate(id, days(0), "PASS").andExpect(status().isCreated());
        calibrate(id, days(-1), "PASS").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anInstrumentIdIsUniqueWithinAnOrganisation() throws Exception {
        Map<String, Object> body = request(facility(owner), days(5));
        as(owner, postJson(INSTRUMENTS, body)).andExpect(status().isCreated());
        as(owner, postJson(INSTRUMENTS, body)).andExpect(status().isConflict());
    }

    @Test
    void aFailureTakesTheInstrumentOutOfUseAndNamesTheInspectionsInDoubt() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        String inspection = inspection();
        use(inspection, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instruments[0].id").value(id));

        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());

        as(owner, get(INSTRUMENTS + "/" + id)).andExpect(jsonPath("$.data.status").value("OUT_OF_SERVICE"));
        as(owner, get(INSTRUMENTS + "/" + id + "/usage"))
                .andExpect(jsonPath("$.data[0].inspectionId").value(inspection))
                .andExpect(jsonPath("$.data[0].suspect").value(true));
        use(inspection(), id).andExpect(status().isUnprocessableEntity());

        calibrate(id, days(0), "ADJUSTED").andExpect(status().isCreated());
        as(owner, get(INSTRUMENTS + "/" + id)).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        as(owner, get(INSTRUMENTS + "/" + id + "/usage")).andExpect(jsonPath("$.data[0].suspect").value(false));
    }

    @Test
    void aFailureRaisesAnNcrAndAsksForADecisionOnEachInspectionInDoubt() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        String inspection = inspection();
        use(inspection, id).andExpect(status().isOk());

        String failed = idOf(body(calibrate(id, days(0), "FAIL").andExpect(status().isCreated())));

        String path = INSTRUMENTS + "/" + id + "/impact-reviews";
        String groups = body(as(owner, get(path))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].calibrationId").value(failed))
                .andExpect(jsonPath("$.data[0].pending").value(1))
                .andExpect(jsonPath("$.data[0].ncrStatus").value("OPEN"))
                .andExpect(jsonPath("$.data[0].reviews[0].inspectionId").value(inspection))
                .andExpect(jsonPath("$.data[0].reviews[0].decision").doesNotExist()));
        String ncr = JsonPath.read(groups, "$.data[0].ncrId");
        String review = JsonPath.read(groups, "$.data[0].reviews[0].id");
        as(owner, get("/api/v1/ncrs/" + ncr))
                .andExpect(jsonPath("$.data.source").value("CALIBRATION"))
                .andExpect(jsonPath("$.data.instrumentId").value(id))
                .andExpect(jsonPath("$.data.severity").value("MAJOR"));
        as(owner, postJson("/api/v1/ncrs/" + ncr + "/cancel", Map.of("note", "Not needed")))
                .andExpect(status().isUnprocessableEntity());
        as(owner, get("/api/v1/inspections/" + inspection))
                .andExpect(jsonPath("$.data.instruments[0].impactReview").value("PENDING"));

        calibrate(id, days(0), "ADJUSTED").andExpect(status().isCreated());
        as(owner, get(path)).andExpect(jsonPath("$.data[0].pending").value(1));

        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson(path + "/" + review + "/decision", Map.of("decision", "REINSPECT", "note", "Re-measure")))
                .andExpect(status().isForbidden());
        as(owner, postJson(path + "/" + review + "/decision", Map.of("decision", "REINSPECT")))
                .andExpect(status().isBadRequest());
        // Still open, it has released nothing to hold.
        as(owner, postJson(path + "/" + review + "/decision",
                        Map.of("decision", "REINSPECT", "note", "Drift exceeds the DIA tolerance")))
                .andExpect(status().isUnprocessableEntity());
        as(owner, postJson(path + "/" + review + "/decision",
                        Map.of("decision", "RESULT_STANDS", "note", "Drift is well inside the DIA tolerance")))
                .andExpect(jsonPath("$.data.decision").value("RESULT_STANDS"))
                .andExpect(jsonPath("$.data.decidedByName").isNotEmpty());
        as(owner, postJson(path + "/" + review + "/decision", Map.of("decision", "REINSPECT", "note", "Changed mind")))
                .andExpect(status().isUnprocessableEntity());
        as(owner, get(path)).andExpect(jsonPath("$.data[0].pending").value(0));
        as(owner, get("/api/v1/inspections/" + inspection))
                .andExpect(jsonPath("$.data.instruments[0].impactReview").value("RESULT_STANDS"));
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE calibration_impact_reviews SET decision = 'REINSPECT' WHERE id = ?", UUID.fromString(review))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reinspectHoldsTheBatchAndTheNcrClosesOnlyOnceEveryInspectionIsDecided() throws Exception {
        updateSettings(owner, Map.of("capaRequiredFrom", "NEVER"));
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        Inspected doubtedBatch = acceptedBatch(id);
        Inspected trustedBatch = acceptedBatch(id);
        String doubted = doubtedBatch.batch();
        String trusted = trustedBatch.batch();
        String customer = customer();
        dispatch(customer, doubted, "2", List.of()).andExpect(status().isCreated());
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());

        String groups = body(as(owner, get(INSTRUMENTS + "/" + id + "/impact-reviews"))
                .andExpect(jsonPath("$.data[0].pending").value(2)));
        String ncr = "/api/v1/ncrs/" + JsonPath.read(groups, "$.data[0].ncrId");
        as(owner, postJson(ncr + "/assign", Map.of("assigneeId", manager.userId()))).andExpect(status().isOk());
        as(owner, postJson(ncr + "/containment", Map.of("containmentAction", "Instrument withdrawn")))
                .andExpect(status().isOk());
        String category = idOf(body(as(owner, postJson("/api/v1/defect-categories",
                Map.of("code", "M" + code(), "name", "Measurement"))).andExpect(status().isCreated())));
        as(owner, postJson(ncr + "/investigation", Map.of("rootCause", "Dropped caliper", "defectCategoryId", category)))
                .andExpect(status().isOk());
        as(owner, postJson(ncr + "/request-verification", Map.of("note", "Reviewed"))).andExpect(status().isOk());
        as(manager, postJson(ncr + "/close", Map.of("note", "Done")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2 still waiting")));

        decide(id, doubtedBatch.inspection(), "REINSPECT").andExpect(status().isOk());
        assertThat(statusOf(doubted)).isEqualTo("ON_HOLD");
        dispatch(customer, doubted, "1", List.of()).andExpect(status().isUnprocessableEntity());
        as(manager, postJson(ncr + "/close", Map.of("note", "Done")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 still waiting")));

        decide(id, trustedBatch.inspection(), "RESULT_STANDS").andExpect(status().isOk());
        assertThat(statusOf(trusted)).isEqualTo("RELEASED");
        as(manager, postJson(ncr + "/close", Map.of("note", "Done")))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        pass(reinspectionOf(doubtedBatch.inspection()), null);
        assertThat(statusOf(doubted)).isEqualTo("RELEASED");
        dispatch(customer, doubted, "1", List.of()).andExpect(status().isCreated());
    }

    @Test
    void reinspectSendsAReleasedLotBackForInspection() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        Lot lot = lot(material(owner, true), "10");
        String inspection = accepted("receiptItemId", lot.id(), "IQC", id);
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "4").andExpect(status().isOk());
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());

        decide(id, inspection, "REINSPECT").andExpect(status().isOk());
        consumeLot(batch, lot.id(), "1").andExpect(status().isUnprocessableEntity());

        pass(reinspectionOf(inspection), null);
        consumeLot(batch, lot.id(), "1").andExpect(status().isOk());
    }

    @Test
    void reinspectStartsTheNewInspectionWithTheSameTemplateOnlyOnce() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        Lot lot = lot(material(owner, true), "10");
        String inspection = accepted("receiptItemId", lot.id(), "IQC", id);
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());
        decide(id, inspection, "REINSPECT").andExpect(status().isOk());

        String later = reinspectionOf(inspection);
        String earlierBody = body(as(owner, get("/api/v1/inspections/" + inspection)));
        as(owner, get("/api/v1/inspections/" + later))
                .andExpect(jsonPath("$.data.inspectionType").value("IQC"))
                .andExpect(jsonPath("$.data.receiptItemId").value(lot.id()))
                .andExpect(jsonPath("$.data.specification.templateName")
                        .value((String) JsonPath.read(earlierBody, "$.data.specification.templateName")));
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INSPECTION_REINSPECTION_STARTED'", Long.class)))
                .isEqualTo(1);
        // Starting it by hand as well is refused: the lot is already being inspected.
        inspect("receiptItemId", lot.id(), "IQC").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aReinspectionThatCannotStartLeavesTheDecisionAndTheHoldInPlace() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        Lot lot = lot(material(owner, true), "10");
        String inspection = accepted("receiptItemId", lot.id(), "IQC", id);
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());
        // The template the lot was inspected with is no longer usable.
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE inspection_templates SET archived_at = now() WHERE id = (SELECT v.template_id FROM inspections i"
                        + " JOIN inspection_template_versions v ON v.id = i.template_version_id WHERE i.id = ?::uuid)",
                inspection));

        decide(id, inspection, "REINSPECT").andExpect(status().isOk());
        as(owner, get("/api/v1/inspections/" + inspection)).andExpect(jsonPath("$.data.reinspectedBy").doesNotExist());
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "1").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theReviewShowsWhatTheDoubtedGoodsAlreadyBecameAndWhoHoldsThem() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        Lot lot = lot(material(owner, true), "10");
        String inspection = accepted("receiptItemId", lot.id(), "IQC", id);
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "4").andExpect(status().isOk());
        complete(batch, "4").andExpect(status().isOk());
        accepted("productionBatchId", batch, "FQC", null);
        dispatch(customer(), batch, "2", List.of()).andExpect(status().isCreated());

        as(owner, get("/api/v1/traceability/inspections/" + inspection + "/reach"))
                .andExpect(jsonPath("$.data.usedIn[0]").value(numberOf(batch)))
                .andExpect(jsonPath("$.data.customers[0]").value("Tata Motors"))
                .andExpect(jsonPath("$.data.recallable[0]").value(numberOf(batch)));
        as(register(), get("/api/v1/traceability/inspections/" + inspection + "/reach"))
                .andExpect(status().isNotFound());
    }

    @Test
    void eachMeasurementNamesItsInstrumentAndAFailureNamesTheMeasurementsInDoubt() throws Exception {
        String caliper = instrument(days(30));
        calibrate(caliper, days(-10), "PASS").andExpect(status().isCreated());
        String overdue = instrument(days(-1));
        Lot lot = lot(material(owner, true), "10");
        String inspection = idOf(body(inspect("receiptItemId", lot.id(), "IQC").andExpect(status().isCreated())));
        String path = "/api/v1/inspections/" + inspection;

        // Naming an instrument on a result records its use, with the same checks.
        as(owner, postJson(path + "/execute", Map.of("results", List.of(Map.of(
                        "parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.0", "instrumentId", overdue)))))
                .andExpect(status().isUnprocessableEntity());
        as(owner, postJson(path + "/execute", Map.of("results", List.of(Map.of(
                        "parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.0", "instrumentId", caliper)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].instrumentId").value(caliper))
                .andExpect(jsonPath("$.data.instruments[0].id").value(caliper))
                .andExpect(jsonPath("$.data.instruments[0].parameters[0]").value("DIA"));
        // Saving the value again without naming one keeps the instrument named before.
        as(owner, postJson(path + "/execute", Map.of("results", List.of(Map.of(
                        "parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.1")))))
                .andExpect(jsonPath("$.data.results[0].instrumentId").value(caliper));
        pass(inspection, null);

        calibrate(caliper, days(0), "FAIL").andExpect(status().isCreated());
        as(owner, get(INSTRUMENTS + "/" + caliper + "/impact-reviews"))
                .andExpect(jsonPath("$.data[0].reviews[0].inspectionId").value(inspection))
                .andExpect(jsonPath("$.data[0].reviews[0].parameters[0]").value("DIA"));
    }

    @Test
    void aFailureWithNothingInDoubtRaisesNoNcr() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());
        as(owner, get(INSTRUMENTS + "/" + id + "/impact-reviews")).andExpect(jsonPath("$.data.length()").value(0));
        as(owner, get("/api/v1/ncrs?size=100")).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void anotherOrganisationCannotDecideAReview() throws Exception {
        String id = instrument(days(30));
        calibrate(id, days(-10), "PASS").andExpect(status().isCreated());
        use(inspection(), id).andExpect(status().isOk());
        calibrate(id, days(0), "FAIL").andExpect(status().isCreated());
        String review = JsonPath.read(body(as(owner, get(INSTRUMENTS + "/" + id + "/impact-reviews"))),
                "$.data[0].reviews[0].id");
        Session stranger = register();
        as(stranger, postJson(INSTRUMENTS + "/" + id + "/impact-reviews/" + review + "/decision",
                        Map.of("decision", "RESULT_STANDS", "note", "x")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOverdueInstrumentCannotBeUsedOnAnInspectionNorOffered() throws Exception {
        String id = instrument(days(-1));
        use(inspection(), id).andExpect(status().isUnprocessableEntity());
        as(owner, get(INSTRUMENTS + "/usable")).andExpect(jsonPath("$.data.totalItems").value(0));
        instrument(days(3));
        as(owner, get(INSTRUMENTS + "/usable")).andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void historyCannotBeRewritten() throws Exception {
        String id = instrument(days(5));
        calibrate(id, days(0), "PASS").andExpect(status().isCreated());
        assertThatThrownBy(() -> asDatabaseSuperuser().update(
                "UPDATE calibration_records SET result = 'FAIL' WHERE instrument_id = ?", UUID.fromString(id)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void anotherOrganisationCannotReachAnInstrument() throws Exception {
        String id = instrument(days(5));
        Session stranger = register();
        as(stranger, get(INSTRUMENTS + "/" + id)).andExpect(status().isNotFound());
        as(stranger, get(INSTRUMENTS + "/" + id + "/qr.svg")).andExpect(status().isNotFound());
        as(stranger, get(INSTRUMENTS)).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void aMemberLimitedToAFacilitySeesOnlyItsInstruments() throws Exception {
        String here = facility(owner);
        String mine = instrument(here, days(5));
        String other = instrument(days(5));
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, here);

        as(limited, get(INSTRUMENTS)).andExpect(jsonPath("$.data.totalItems").value(1));
        as(limited, get(INSTRUMENTS + "/" + mine)).andExpect(status().isOk());
        as(limited, get(INSTRUMENTS + "/" + other)).andExpect(status().isNotFound());
    }

    @Test
    void aViewerCannotChangeTheRegister() throws Exception {
        String id = instrument(days(5));
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get(INSTRUMENTS + "/" + id)).andExpect(status().isOk());
        as(viewer, postJson(INSTRUMENTS + "/" + id + "/calibrations", Map.of("calibratedOn", days(0), "result", "PASS",
                        "performedBy", "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theQrCodeLinksToTheInstrument() throws Exception {
        String id = instrument(days(5));
        as(owner, get(INSTRUMENTS + "/" + id + "/qr.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
        assertThat(QrCodes.svg("https://example.com/calibration/" + id)).startsWith("<svg").contains("<path");
    }
}
