package com.qualityos.traceability.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.traceability.dto.TraceabilityDtos.CancelTransferRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateTransferRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ReceiveTransferRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.TransferResponse;
import com.qualityos.traceability.entity.StockTransfer;
import com.qualityos.traceability.service.StockTransferService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-transfers")
@Tag(name = "Stock transfers between plants")
public class StockTransferController {

    private static final Set<String> SORTABLE = Set.of("transferNumber", "sentOn", "status", "createdAt");

    private final StockTransferService transfers;

    StockTransferController(StockTransferService transfers) {
        this.transfers = transfers;
    }

    @GetMapping
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<PageResponse<TransferResponse>> list(
            @RequestParam(required = false) StockTransfer.Status status,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(transfers.list(status, facilityId, Pages.of(page, size, sort, SORTABLE, "createdAt")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<TransferResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(transfers.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> send(@Valid @RequestBody CreateTransferRequest request) {
        return ApiResponse.ok(transfers.send(request));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<TransferResponse> receive(@PathVariable UUID id, @Valid @RequestBody ReceiveTransferRequest request) {
        return ApiResponse.ok(transfers.receive(id, request.receivedOn()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<TransferResponse> cancel(@PathVariable UUID id, @Valid @RequestBody CancelTransferRequest request) {
        return ApiResponse.ok(transfers.cancel(id, request.reason()));
    }
}
