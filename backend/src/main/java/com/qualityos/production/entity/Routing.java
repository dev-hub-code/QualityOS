package com.qualityos.production.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The operations, in order, that make a product. */
@Entity
@Table(name = "routings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Routing extends RevisionedDefinition {

    public Routing(UUID productId, int revision, String changeNote) {
        super(productId, revision, changeNote);
    }
}
