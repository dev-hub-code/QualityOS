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

    /** @return whether the current user may see this parent's files without {@link #viewPermission()}: it is theirs */
    default boolean callerMayView(UUID entityId) {
        return false;
    }

    /**
     * @return whether the current user may add or remove this parent's files without {@link #managePermission()}: it is
     *     theirs
     */
    default boolean callerMayManage(UUID entityId) {
        return false;
    }

    /** @return whether the parent's files are now part of a closed record, and can no longer change */
    default boolean locked(UUID entityId) {
        return false;
    }
}
