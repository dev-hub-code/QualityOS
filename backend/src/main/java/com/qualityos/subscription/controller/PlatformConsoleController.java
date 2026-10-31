package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.subscription.dto.PlatformConsoleDtos.AddNoteRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.Announcement;
import com.qualityos.subscription.dto.PlatformConsoleDtos.OpenTicketRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.PlatformSettings;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SaveAnnouncementRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SaveSettingsRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SupportError;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketList;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketNote;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketStatusRequest;
import com.qualityos.subscription.service.PlatformConsole;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform console")
@PreAuthorize(Require.PLATFORM_ADMIN)
@Validated
class PlatformConsoleController {

    private final PlatformConsole console;

    PlatformConsoleController(PlatformConsole console) {
        this.console = console;
    }

    @GetMapping("/settings")
    public ApiResponse<PlatformSettings> settings() {
        return ApiResponse.ok(console.settings());
    }

    @PutMapping("/settings")
    @PreAuthorize(Require.PLATFORM_SETTINGS_MANAGE)
    public ApiResponse<PlatformSettings> saveSettings(@Valid @RequestBody SaveSettingsRequest request) {
        return ApiResponse.ok(console.saveSettings(request));
    }

    @GetMapping("/announcements")
    public ApiResponse<List<Announcement>> announcements() {
        return ApiResponse.ok(console.announcements());
    }

    @PostMapping("/announcements")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void createAnnouncement(@Valid @RequestBody SaveAnnouncementRequest request) {
        console.saveAnnouncement(null, request);
    }

    @PutMapping("/announcements/{id}")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveAnnouncement(@PathVariable UUID id, @Valid @RequestBody SaveAnnouncementRequest request) {
        console.saveAnnouncement(id, request);
    }

    @GetMapping("/support/tickets")
    public ApiResponse<TicketList> tickets(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(console.tickets(tenantId, status, page, size));
    }

    @PostMapping("/support/tickets")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@Valid @RequestBody OpenTicketRequest request) {
        console.open(request);
    }

    @PutMapping("/support/tickets/{id}/status")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@PathVariable UUID id, @Valid @RequestBody TicketStatusRequest request) {
        console.changeStatus(id, request);
    }

    @GetMapping("/support/tickets/{id}/notes")
    public ApiResponse<List<TicketNote>> notes(@PathVariable UUID id) {
        return ApiResponse.ok(console.notes(id));
    }

    @PostMapping("/support/tickets/{id}/notes")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addNote(@PathVariable UUID id, @Valid @RequestBody AddNoteRequest request) {
        console.addNote(id, request);
    }

    @GetMapping("/support/organisations/{tenantId}/errors")
    public ApiResponse<List<SupportError>> errors(@PathVariable UUID tenantId) {
        return ApiResponse.ok(console.errors(tenantId));
    }
}
