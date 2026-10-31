package com.qualityos.common.attachment;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {

    private static final int HEADER_BYTES = 16;
    private static final int MAX_NAME_LENGTH = 200;

    private final AttachmentRepository attachments;
    private final FileStorage storage;
    private final AuditService audit;
    private final Map<String, AttachmentPolicy> policies;

    AttachmentService(
            AttachmentRepository attachments,
            FileStorage storage,
            AuditService audit,
            List<AttachmentPolicy> policies) {
        this.attachments = attachments;
        this.storage = storage;
        this.audit = audit;
        this.policies = policies.stream().collect(Collectors.toMap(AttachmentPolicy::entityType, Function.identity()));
    }

    public record AttachmentResponse(
            UUID id,
            String fileName,
            String contentType,
            long sizeBytes,
            String category,
            Instant uploadedAt) {
    }

    public record Download(String fileName, String contentType, long sizeBytes, InputStream content) {
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(String entityType, UUID entityId) {
        AttachmentPolicy policy = authorize(entityType, entityId, AttachmentPolicy::viewPermission);
        return attachments
                .findByEntityTypeAndEntityIdAndDeletedAtIsNullOrderByCreatedAtDesc(policy.entityType(), entityId)
                .stream()
                .map(AttachmentService::toResponse)
                .toList();
    }

    @Transactional
    public AttachmentResponse upload(String entityType, UUID entityId, String category, MultipartFile file) {
        AttachmentPolicy policy = authorize(entityType, entityId, AttachmentPolicy::managePermission);
        if (file.isEmpty()) {
            throw new BusinessRuleException("The file is empty.");
        }
        String contentType = detectType(file);
        String storageKey = TenantContext.getCurrentTenantId() + "/" + UUID.randomUUID();
        Attachment attachment = new Attachment(
                policy.entityType(), entityId, category, sanitize(file.getOriginalFilename()),
                contentType, file.getSize(), storeAndHash(storageKey, file), storageKey);
        attachments.save(attachment);
        audit.record("ATTACHMENT_ADDED", policy.entityType(), entityId, null, Map.of("file", attachment.getFileName()));
        return toResponse(attachment);
    }

    @Transactional(readOnly = true)
    public Download download(UUID id) throws IOException {
        Attachment attachment = load(id);
        authorize(attachment.getEntityType(), attachment.getEntityId(), AttachmentPolicy::viewPermission);
        return new Download(
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                storage.open(attachment.getStorageKey()));
    }

    /** Hides the file rather than deleting it, because quality records must keep their evidence. */
    @Transactional
    public void delete(UUID id) {
        Attachment attachment = load(id);
        authorize(attachment.getEntityType(), attachment.getEntityId(), AttachmentPolicy::managePermission);
        attachment.setDeletedAt(Instant.now());
        audit.record("ATTACHMENT_REMOVED", attachment.getEntityType(), attachment.getEntityId(),
                Map.of("file", attachment.getFileName()), null);
    }

    private Attachment load(UUID id) {
        return attachments.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", id));
    }

    private AttachmentPolicy authorize(String entityType, UUID entityId, Function<AttachmentPolicy, String> permission) {
        AttachmentPolicy policy = policies.get(entityType);
        if (policy == null || !policy.parentExists(entityId)) {
            throw new ResourceNotFoundException("Record", entityId);
        }
        boolean allowed = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission.apply(policy)::equals);
        if (!allowed) {
            throw new AccessDeniedException("Missing " + permission.apply(policy));
        }
        return policy;
    }

    private static String detectType(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return FileTypeDetector.detect(Arrays.copyOf(content.readNBytes(HEADER_BYTES), HEADER_BYTES))
                    .orElseThrow(() -> new BusinessRuleException("Only PDF, PNG, JPEG and Office files can be uploaded."));
        } catch (IOException ex) {
            throw new QualityOsException(ErrorCode.INTERNAL_ERROR, "The file could not be read.");
        }
    }

    private String storeAndHash(String storageKey, MultipartFile file) {
        try (DigestInputStream content =
                new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            storage.store(storageKey, content);
            return HexFormat.of().formatHex(content.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Could not store attachment", ex);
        }
    }

    /** Drops any client path and control characters; the name is only ever used for display and download. */
    static String sanitize(String name) {
        String base = name == null ? "file" : name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        String clean = base.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (clean.isEmpty()) {
            return "file";
        }
        return clean.length() > MAX_NAME_LENGTH ? clean.substring(clean.length() - MAX_NAME_LENGTH) : clean;
    }

    private static AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getCategory(),
                attachment.getCreatedAt());
    }
}
