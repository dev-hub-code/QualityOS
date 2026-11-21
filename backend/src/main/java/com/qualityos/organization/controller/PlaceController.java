package com.qualityos.organization.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.organization.dto.PlaceDtos.StateResponse;
import com.qualityos.organization.service.IndiaPlaces;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** States and cities to suggest on addresses. The same for everyone, and it changes only with a release. */
@RestController
@RequestMapping("/api/v1/places")
@Tag(name = "Places")
public class PlaceController {

    private static final CacheControl CACHE = CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate();

    private final IndiaPlaces places;

    PlaceController(IndiaPlaces places) {
        this.places = places;
    }

    @GetMapping("/states")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<StateResponse>>> states() {
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(places.states()));
    }

    /** {@code gstCode}: the state's two-digit GST code, such as 27. */
    @GetMapping("/states/{gstCode}/cities")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<String>>> cities(@PathVariable String gstCode,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiResponse.ok(places.cities(gstCode, q)));
    }
}
