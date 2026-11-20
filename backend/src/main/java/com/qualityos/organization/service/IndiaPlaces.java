package com.qualityos.organization.service;

import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.organization.dto.PlaceDtos.StateResponse;
import com.qualityos.organization.dto.PlaceDtos.StateWithCities;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * India's states and union territories, with their GST state codes and main cities, bundled with the application. The
 * cities are suggestions, not a complete list: an address may name any city.
 */
@Service
public class IndiaPlaces {

    private static final String DATA = "reference/india-states.json";

    private final List<StateWithCities> states;
    private final Map<String, StateWithCities> byCode;

    IndiaPlaces() {
        try (InputStream in = new ClassPathResource(DATA).getInputStream()) {
            List<StateWithCities> loaded = JsonMapper.builder().build()
                    .readValue(in, new TypeReference<List<StateWithCities>>() { });
            this.states = loaded.stream()
                    .map(state -> new StateWithCities(state.gstCode(), state.name(), state.kind(),
                            state.cities().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()))
                    .sorted(Comparator.comparing(StateWithCities::name)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DATA, e);
        }
        this.byCode = states.stream().collect(Collectors.toUnmodifiableMap(StateWithCities::gstCode,
                Function.identity()));
    }

    /** Every state and union territory, by name. */
    public List<StateResponse> states() {
        return states.stream().map(state -> new StateResponse(state.gstCode(), state.name(), state.kind())).toList();
    }

    /** A state's main cities, by name; {@code query} narrows them to those whose name contains it. */
    public List<String> cities(String gstCode, String query) {
        StateWithCities state = byCode.get(gstCode);
        if (state == null) {
            throw new ResourceNotFoundException("State", gstCode);
        }
        if (query == null || query.isBlank()) {
            return state.cities();
        }
        String wanted = query.trim().toLowerCase(Locale.ROOT);
        return state.cities().stream().filter(city -> city.toLowerCase(Locale.ROOT).contains(wanted)).toList();
    }
}
