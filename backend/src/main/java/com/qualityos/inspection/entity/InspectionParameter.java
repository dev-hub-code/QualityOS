package com.qualityos.inspection.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inspection_parameters")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionParameter extends TenantOwnedEntity {

    public enum DataType { NUMERIC, TEXT, BOOLEAN, DATE }

    public enum Criticality { CRITICAL, MAJOR, MINOR }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_version_id")
    private TemplateVersion templateVersion;

    private String code;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private DataType dataType;

    private UUID uomId;
    private BigDecimal nominalValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private BigDecimal tolerance;
    private String expectedText;

    @Enumerated(EnumType.STRING)
    private Criticality criticality = Criticality.MINOR;

    private boolean mandatory = true;
    private boolean perSample = true;
    private boolean evidenceRequired;
    private String inspectionMethod;
    private String remarks;
    private int sortOrder;

    public InspectionParameter(TemplateVersion templateVersion) {
        this.templateVersion = templateVersion;
    }
}
