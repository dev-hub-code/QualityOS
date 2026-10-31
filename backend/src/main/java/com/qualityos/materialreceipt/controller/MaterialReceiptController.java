package com.qualityos.materialreceipt.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.CreateReceiptRequest;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptDetail;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptSummary;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.VoidReceiptRequest;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
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
@RequestMapping("/api/v1/material-receipts")
@Tag(name = "Material receipts")
public class MaterialReceiptController {

    private static final Set<String> SORTABLE = Set.of("grnNumber", "receivedOn", "status");

    private final MaterialReceiptService receipts;

    MaterialReceiptController(MaterialReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping
    @PreAuthorize(Require.PURCHASE_VIEW)
    public ApiResponse<PageResponse<ReceiptSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID purchaseOrderId) {
        return ApiResponse.ok(receipts.list(q, purchaseOrderId, Pages.of(page, size, sort, SORTABLE, "grnNumber")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.PURCHASE_VIEW)
    public ApiResponse<ReceiptDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(receipts.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.RECEIPT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReceiptDetail> create(@Valid @RequestBody CreateReceiptRequest request) {
        return ApiResponse.ok(receipts.create(request));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize(Require.RECEIPT_MANAGE)
    public ApiResponse<ReceiptDetail> voidReceipt(
            @PathVariable UUID id, @Valid @RequestBody VoidReceiptRequest request) {
        return ApiResponse.ok(receipts.voidReceipt(id, request.reason()));
    }
}
