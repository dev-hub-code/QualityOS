package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
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

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> features;

    private int trialDays;
    private int graceDays;
    private int displayOrder;
    private boolean active;

    public BigDecimal priceFor(Subscription.Cycle cycle) {
        return cycle == Subscription.Cycle.ANNUAL ? annualPrice : monthlyPrice;
    }
}
