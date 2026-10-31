package com.qualityos.inspection.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One recorded value. Typed columns keep results analysable; the column used follows the parameter's type. */
@Getter
@Entity
@Table(name = "inspection_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionResult extends TenantOwnedEntity {

    private UUID inspectionId;
    private String parameterCode;
    private int sampleNumber;
    private BigDecimal valueNumeric;
    private String valueText;
    private Boolean valueBoolean;
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    private Inspection.Evaluation evaluation;

    private String remarks;

    public InspectionResult(UUID inspectionId, String parameterCode, int sampleNumber) {
        this.inspectionId = inspectionId;
        this.parameterCode = parameterCode;
        this.sampleNumber = sampleNumber;
    }

    public void record(
            BigDecimal numeric,
            String text,
            Boolean bool,
            LocalDate date,
            Inspection.Evaluation evaluation,
            String remarks) {
        this.valueNumeric = numeric;
        this.valueText = text;
        this.valueBoolean = bool;
        this.valueDate = date;
        this.evaluation = evaluation;
        this.remarks = remarks;
    }

    public String displayValue() {
        if (valueNumeric != null) {
            return valueNumeric.stripTrailingZeros().toPlainString();
        }
        if (valueBoolean != null) {
            return valueBoolean.toString();
        }
        return valueDate != null ? valueDate.toString() : valueText;
    }
}
