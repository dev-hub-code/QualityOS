package com.qualityos.notification.controller;

import com.qualityos.common.tenant.TenantContext;
import com.qualityos.notification.service.NotificationStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.notification.dto.NotificationDtos.NotificationResponse;
import com.qualityos.notification.dto.NotificationDtos.PreferenceResponse;
import com.qualityos.notification.dto.NotificationDtos.UnreadCount;
import com.qualityos.notification.dto.NotificationDtos.UpdatePreferenceRequest;
import com.qualityos.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Needs no permission: each user only ever reaches their own inbox. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final NotificationStream stream;

    NotificationController(NotificationService notifications, NotificationStream stream) {
        this.notifications = notifications;
        this.stream = stream;
    }

    /**
     * Server-sent events: a {@code notifications} event whenever the signed-in user's inbox changes. It names no
     * notification, so the page reads the inbox as usual.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        // Asks a proxy such as nginx not to hold events back.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        return stream.connect(TenantContext.getCurrentTenantId(), TenantContext.getCurrentUserId());
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> inbox(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(notifications.inbox(
                unreadOnly, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        return ApiResponse.ok(notifications.unreadCount());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        notifications.markRead(id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notifications.markAllRead();
    }

    @GetMapping("/preferences")
    public ApiResponse<List<PreferenceResponse>> preferences() {
        return ApiResponse.ok(notifications.preferences());
    }

    @PutMapping("/preferences")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updatePreference(@Valid @RequestBody UpdatePreferenceRequest request) {
        notifications.updatePreference(request.type(), request.emailEnabled());
    }
}
