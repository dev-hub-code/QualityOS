package com.qualityos.common.attachment;

import com.qualityos.common.attachment.AttachmentService.AttachmentResponse;
import com.qualityos.common.attachment.AttachmentService.Download;
import com.qualityos.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Permissions are checked in the service, because they depend on the parent record's module. */
@RestController
@RequestMapping("/api/v1/attachments")
@Tag(name = "Attachments")
public class AttachmentController {

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping
    public ApiResponse<List<AttachmentResponse>> list(@RequestParam String entityType, @RequestParam UUID entityId) {
        return ApiResponse.ok(attachments.list(entityType, entityId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentResponse> upload(
            @RequestParam String entityType,
            @RequestParam UUID entityId,
            @RequestParam(required = false) String category,
            @RequestPart MultipartFile file) {
        return ApiResponse.ok(attachments.upload(entityType, entityId, category, file));
    }

    /** Always a download, and never sniffed, so an uploaded file cannot run as content on our origin. */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) throws IOException {
        Download file = attachments.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .body(new InputStreamResource(file.content()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        attachments.delete(id);
    }
}
