package com.qualityos.traceability.controller;

import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.SearchHit;
import com.qualityos.traceability.dto.TraceabilityDtos.SerialFilter;
import com.qualityos.traceability.dto.TraceabilityDtos.SerialSearchResult;
import com.qualityos.traceability.dto.TraceabilityDtos.SerialTrace;
import com.qualityos.traceability.service.SerialSearchService;
import com.qualityos.traceability.dto.TraceabilityDtos.TraceReport;
import com.qualityos.traceability.service.ProductionService;
import com.qualityos.traceability.service.TraceabilityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/traceability")
@Tag(name = "Traceability")
@PreAuthorize(Require.TRACEABILITY_VIEW)
public class TraceabilityController {

    private final TraceabilityService traceability;
    private final ProductionService production;
    private final SerialSearchService serials;

    TraceabilityController(
            TraceabilityService traceability, ProductionService production, SerialSearchService serials) {
        this.traceability = traceability;
        this.production = production;
        this.serials = serials;
    }

    @GetMapping("/search")
    public ApiResponse<List<SearchHit>> search(@RequestParam String q) {
        return ApiResponse.ok(traceability.search(q));
    }

    @GetMapping("/batches/{batchNumber}")
    public ApiResponse<TraceReport> batch(@PathVariable String batchNumber, Authentication authentication) {
        return ApiResponse.ok(traceability.report(batchNumber, permissions(authentication)));
    }

    @GetMapping("/serials/{serial}")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<SerialTrace> serial(@PathVariable String serial, Authentication authentication) {
        return ApiResponse.ok(traceability.serial(serial, permissions(authentication)));
    }

    @GetMapping("/serials")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<PageResponse<SerialSearchResult>> serials(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) LocalDate madeFrom,
            @RequestParam(required = false) LocalDate madeTo,
            @RequestParam(required = false) LocalDate dispatchedFrom,
            @RequestParam(required = false) LocalDate dispatchedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            Authentication authentication) {
        return ApiResponse.ok(serials.search(
                new SerialFilter(q, field, facilityId, madeFrom, madeTo, dispatchedFrom, dispatchedTo),
                Pages.of(page, size, sort, SerialSearchService.SORTABLE, SerialSearchService.DEFAULT_SORT),
                permissions(authentication).contains(Permissions.INSPECTION_VIEW)));
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<PageResponse<ProductionBatchView>> product(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(production.list(
                null, null, productId, Pages.of(page, size, null, Set.of("batchNumber"), "batchNumber")));
    }

    private static Set<String> permissions(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
