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

    public enum SamplingType { FIXED, PERCENTAGE, FULL, AQL }

    /**
     * What one inspection takes: how many samples, how many nonconforming ones it tolerates, and from how many it is
     * rejected. Only an AQL plan names its code letter and severity; elsewhere the rejection number is Ac + 1.
     */
    public record SamplingPlan(int sampleSize, int acceptanceNumber, int rejectionNumber, String codeLetter,
            AqlTable.Severity severity, SecondSample secondSample) {

        public SamplingPlan(int sampleSize, int acceptanceNumber, int rejectionNumber, String codeLetter,
                AqlTable.Severity severity) {
            this(sampleSize, acceptanceNumber, rejectionNumber, codeLetter, severity, null);
        }

        public SamplingPlan(int sampleSize, int acceptanceNumber) {
            this(sampleSize, acceptanceNumber, acceptanceNumber + 1, null, null);
        }
    }

    /**
     * Under double sampling, the second sample a lot takes when the first neither accepts nor rejects it. Its acceptance
     * and rejection numbers count the nonconforming units of both samples together.
     */
    public record SecondSample(int sampleSize, int acceptanceNumber, int rejectionNumber) {
    }

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

    /** For AQL sampling: the acceptable quality limit, in percent nonconforming, from the standard's columns. */
    @Setter
    private BigDecimal aql;

    /** For AQL sampling: the general inspection level. */
    @Setter
    @Enumerated(EnumType.STRING)
    private AqlTable.Level inspectionLevel;

    /** For AQL sampling: double sampling where the standard has a plan for the lot, single sampling elsewhere. */
    @Setter
    private boolean doubleSampling;

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

    public int samplesFor(BigDecimal quantity) {
        return planFor(quantity).sampleSize();
    }

    /**
     * The plan for a lot of this quantity. Samples are counted whole, so a fraction always rounds up, and so does the
     * lot size an AQL plan looks up. Other plans keep the version's own acceptance number.
     */
    public SamplingPlan planFor(BigDecimal quantity) {
        return planFor(quantity, AqlTable.Severity.NORMAL);
    }

    /** @param severity for an AQL plan, where its series stands under the switching rules */
    public SamplingPlan planFor(BigDecimal quantity, AqlTable.Severity severity) {
        if (samplingType == SamplingType.AQL) {
            long lot = quantity.setScale(0, RoundingMode.CEILING).longValueExact();
            if (doubleSampling) {
                var twice = AqlTable.doublePlan(lot, inspectionLevel, aql, severity);
                if (twice.isPresent()) {
                    AqlTable.DoublePlan plan = twice.get();
                    checked(2 * plan.sampleSize());
                    return new SamplingPlan(plan.sampleSize(), plan.firstAcceptance(), plan.firstRejection(),
                            String.valueOf(plan.codeLetter()), severity, new SecondSample(plan.sampleSize(),
                                    plan.secondAcceptance(), plan.secondRejection()));
                }
            }
            AqlTable.Plan plan = AqlTable.plan(lot, inspectionLevel, aql, severity);
            return new SamplingPlan(checked(plan.sampleSize()), plan.acceptanceNumber(), plan.rejectionNumber(),
                    String.valueOf(plan.codeLetter()), severity);
        }
        int samples = switch (samplingType) {
            case FIXED -> sampleSize;
            case PERCENTAGE -> quantity.multiply(samplePercent).movePointLeft(2)
                    .setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(MAX_SAMPLES + 1L)).intValue();
            case FULL -> quantity.setScale(0, RoundingMode.CEILING)
                    .min(BigDecimal.valueOf(MAX_SAMPLES + 1L)).intValue();
            case AQL -> throw new IllegalStateException();
        };
        return new SamplingPlan(Math.max(checked(samples), 1), acceptanceNumber);
    }

    private static int checked(int samples) {
        if (samples > MAX_SAMPLES) {
            throw new BusinessRuleException(
                    "This plan needs over %d samples. Use a fixed sample size.".formatted(MAX_SAMPLES));
        }
        return samples;
    }
}
