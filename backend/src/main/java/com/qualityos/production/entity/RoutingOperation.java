package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
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
@Table(name = "routing_operations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutingOperation extends TenantOwnedEntity {

    private UUID routingId;
    private int sequence;
    private String name;
    private UUID workCenterId;
    private BigDecimal setupMinutes = BigDecimal.ZERO;
    /** Per unit of the product. */
    private BigDecimal runMinutes = BigDecimal.ZERO;
    private String instructions;

    public RoutingOperation(UUID routingId) {
        this.routingId = routingId;
    }
}
