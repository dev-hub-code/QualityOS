package com.qualityos.quality.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "capa_actions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapaAction extends TenantOwnedEntity {

    public enum Type { CORRECTIVE, PREVENTIVE }

    private UUID capaId;

    @Enumerated(EnumType.STRING)
    private Type actionType;

    private String description;
    private UUID ownerId;
    private LocalDate targetDate;
    private Instant completedAt;
    private String completionNote;

    public CapaAction(UUID capaId, Type actionType, String description, UUID ownerId, LocalDate targetDate) {
        this.capaId = capaId;
        this.actionType = actionType;
        this.description = description;
        this.ownerId = ownerId;
        this.targetDate = targetDate;
    }

    public boolean isDone() {
        return completedAt != null;
    }

    public void complete(String note, Instant now) {
        if (isDone()) {
            throw new BusinessRuleException("This action is already complete.");
        }
        completionNote = note;
        completedAt = now;
    }
}
