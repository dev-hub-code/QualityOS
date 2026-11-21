package com.qualityos.organization.dto;

import java.util.List;

/** India's states and union territories and their main cities, to suggest on addresses. */
public final class PlaceDtos {

    private PlaceDtos() {
    }

    public enum StateKind {
        STATE, UNION_TERRITORY
    }

    /** {@code gstCode}: the two digits a GSTIN registered there starts with, such as 27 for Maharashtra. */
    public record StateResponse(String gstCode, String name, StateKind kind) {
    }

    /** As bundled: a state with its cities. */
    public record StateWithCities(String gstCode, String name, StateKind kind, List<String> cities) {
    }
}
