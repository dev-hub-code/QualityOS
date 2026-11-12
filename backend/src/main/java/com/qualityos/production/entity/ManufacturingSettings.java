package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "manufacturing_settings")
@NoArgsConstructor
public class ManufacturingSettings extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    private ManufacturingModel manufacturingModel = ManufacturingModel.DISCRETE;

    private boolean separateApprover = true;
}
