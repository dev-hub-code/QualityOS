package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.SchedulingDtos.SchedulingBoard;
import com.qualityos.production.service.SchedulingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manufacturing/scheduling")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.WORK_ORDER_VIEW)
public class SchedulingController {

    private final SchedulingService scheduling;

    SchedulingController(SchedulingService scheduling) {
        this.scheduling = scheduling;
    }

    /** A facility's work centres day by day, load against capacity, open orders with warnings, and conflicts. */
    @GetMapping
    public ApiResponse<SchedulingBoard> board(
            @RequestParam UUID facilityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(scheduling.board(facilityId, from, to));
    }
}
