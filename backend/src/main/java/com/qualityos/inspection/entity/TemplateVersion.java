package com.qualityos.inspection.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "inspection_template_versions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateVersion extends TenantOwnedEntity {

    public enum Status { DRAFT, PUBLISHED, SUPERSEDED }

    public enum SamplingType { FIXED, PERCENTAGE, FULL }

    /** Above this, entering every sample by hand is not realistic, and a fixed plan fits better. */
    public static final int MAX_SAMPLES = 500;

    private UUID templateId;
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Setter
    private String changeNote;

    @Setter
    @Enumerated(EnumType.STRING)
    private SamplingType samplingType = SamplingType.FIXED;

    @Setter
    private Integer sampleSize = 1;

    @Setter
    private BigDecimal samplePercent;

    @Setter
    private int acceptanceNumber;

    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private UUID publishedBy;
    private Instant publishedAt;

    @OrderBy("sortOrder")
    @OneToMany(mappedBy = "templateVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InspectionParameter> parameters = new ArrayList<>();

    public TemplateVersion(UUID templateId, int versionNumber) {
        this.templateId = templateId;
        this.versionNumber = versionNumber;
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleException("A published version cannot be changed. Create a new version.");
        }
    }

    public void publish(LocalDate effectiveFrom, UUID actor) {
        requireDraft();
        if (parameters.isEmpty()) {
            throw new BusinessRuleException("Add at least one parameter before publishing.");
        }
        status = Status.PUBLISHED;
        this.effectiveFrom = effectiveFrom;
        publishedBy = actor;
        publishedAt = Instant.now();
    }

    public void supersede(LocalDate effectiveTo) {
        status = Status.SUPERSEDED;
        this.effectiveTo = effectiveTo;
    }

    /** Samples are counted whole, so a fraction always rounds up. */
    public int samplesFor(BigDecimal quantity) {
        int samples = switch (samplingType) {
            case FIXED -> sampleSize;
            case PERCENTAGE -> quantity.multiply(samplePercent).movePointLeft(2)
                    .setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(MAX_SAMPLES + 1L)).intValue();
            case FULL -> quantity.setScale(0, RoundingMode.CEILING)
                    .min(BigDecimal.valueOf(MAX_SAMPLES + 1L)).intValue();
        };
        if (samples > MAX_SAMPLES) {
            throw new BusinessRuleException(
                    "This plan needs over %d samples. Use a fixed sample size.".formatted(MAX_SAMPLES));
        }
        return Math.max(samples, 1);
    }
}
