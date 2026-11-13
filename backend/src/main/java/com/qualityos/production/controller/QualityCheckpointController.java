package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ProductionQualityDtos.CheckpointResponse;
import com.qualityos.production.dto.ProductionQualityDtos.SaveCheckpointRequest;
import com.qualityos.production.service.QualityCheckpointService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Where production needs an inspection. Released work orders keep the gates they were given. */
@RestController
@RequestMapping("/api/v1/manufacturing/quality-checkpoints")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class QualityCheckpointController {

    private final QualityCheckpointService checkpoints;

    QualityCheckpointController(QualityCheckpointService checkpoints) {
        this.checkpoints = checkpoints;
    }

    @GetMapping
    public ApiResponse<List<CheckpointResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.ok(checkpoints.list(includeArchived));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.QUALITY_CHECKPOINT_MANAGE)
    public ApiResponse<CheckpointResponse> create(@Valid @RequestBody SaveCheckpointRequest request) {
        return ApiResponse.ok(checkpoints.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.QUALITY_CHECKPOINT_MANAGE)
    public ApiResponse<CheckpointResponse> update(
            @PathVariable UUID id, @Valid @RequestBody SaveCheckpointRequest request) {
        return ApiResponse.ok(checkpoints.update(id, request));
    }

    /** Archives it: nothing is deleted. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Require.QUALITY_CHECKPOINT_MANAGE)
    public void archive(@PathVariable UUID id) {
        checkpoints.archive(id);
    }
}
