package com.qualityos.common.attachment;

import java.util.UUID;

/**
 * Lets a module own attachments. An attachment has no permissions of its own: access follows the parent record,
 * so that a file is reachable only by someone who may see that record.
 */
public interface AttachmentPolicy {

    /** Stored in {@code attachments.entity_type}, for example {@code SUPPLIER}. */
    String entityType();

    String viewPermission();

    String managePermission();

    /** @return whether the parent exists in the current tenant */
    boolean parentExists(UUID entityId);
}
