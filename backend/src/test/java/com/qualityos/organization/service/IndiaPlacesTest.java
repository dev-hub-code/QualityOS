package com.qualityos.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.organization.dto.PlaceDtos.StateKind;
import com.qualityos.organization.dto.PlaceDtos.StateResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndiaPlacesTest {

    private final IndiaPlaces places = new IndiaPlaces();

    @Test
    void listsEveryStateAndUnionTerritoryOnceWithItsGstCode() {
        List<StateResponse> states = places.states();
        assertThat(states).hasSize(36);
        assertThat(states.stream().filter(state -> state.kind() == StateKind.STATE)).hasSize(28);
        assertThat(states.stream().map(StateResponse::gstCode)).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("\\d{2}"));
        assertThat(states.stream().map(StateResponse::name).toList()).doesNotHaveDuplicates().isSorted();
        assertThat(states).contains(new StateResponse("27", "Maharashtra", StateKind.STATE),
                new StateResponse("07", "Delhi", StateKind.UNION_TERRITORY));
    }

    @Test
    void everyStateHasCitiesInNameOrderWithoutRepeats() {
        for (StateResponse state : places.states()) {
            List<String> cities = places.cities(state.gstCode(), null);
            assertThat(cities).as(state.name()).isNotEmpty().doesNotHaveDuplicates()
                    .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER)
                    .allMatch(city -> city.equals(city.strip()));
        }
    }

    @Test
    void narrowsCitiesByWhatIsTyped() {
        assertThat(places.cities("27", "pun")).containsExactly("Pune");
        assertThat(places.cities("27", "  NAG ")).contains("Nagpur").doesNotContain("Mumbai");
        assertThatThrownBy(() -> places.cities("99", null)).isInstanceOf(ResourceNotFoundException.class);
    }
}
