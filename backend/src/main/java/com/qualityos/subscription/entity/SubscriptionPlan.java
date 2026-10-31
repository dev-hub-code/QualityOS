package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Belongs to the platform. A null price means the plan is agreed by hand, and a null limit means no limit. */
@Getter
@Setter
@Entity
@Table(name = "subscription_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPlan extends AuditedEntity {

    public static final String TRIAL_CODE = "PROFESSIONAL";

    private String code;
    private String name;
    private String description;
    private BigDecimal monthlyPrice;
    private BigDecimal annualPrice;
    private BigDecimal taxPercent;
    private Integer maxUsers;
    private Integer maxFacilities;
    private Integer maxStorageMb;
    private Integer maxMonthlyInspections;
    private Integer maxAttachments;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> features;

    /** Values for the features that have one, by code. A feature's presence in {@link #features} is what enables it. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> featureValues = new HashMap<>();

    private int trialDays;
    private int graceDays;
    private int displayOrder;
    private boolean active;
    private String currency;
    private boolean publicVisible;
    private Instant archivedAt;

    /** A new plan, or a copy of another, starts out of sale until someone chooses to offer it. */
    public SubscriptionPlan(String code, String name) {
        this.code = code;
        this.name = name;
        this.description = "";
        this.taxPercent = new BigDecimal("18");
        this.features = new ArrayList<>();
        this.trialDays = 14;
        this.graceDays = 7;
        this.currency = "INR";
        this.publicVisible = false;
    }

    public BigDecimal priceFor(Subscription.Cycle cycle) {
        return cycle == Subscription.Cycle.ANNUAL ? annualPrice : monthlyPrice;
    }
}
