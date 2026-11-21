package com.qualityos.organization.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.organization.dto.HolidayCalendarDtos.HolidayCalendar;
import com.qualityos.organization.service.HolidayCalendarService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/holiday-calendar")
@Tag(name = "Facilities")
public class HolidayCalendarController {

    private final HolidayCalendarService calendar;

    HolidayCalendarController(HolidayCalendarService calendar) {
        this.calendar = calendar;
    }

    /** Every member can see when their facilities are closed; no permission is needed. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HolidayCalendar> get(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(calendar.calendar(from, to));
    }
}
