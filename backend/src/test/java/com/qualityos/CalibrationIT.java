package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
