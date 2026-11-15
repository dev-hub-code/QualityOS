package com.qualityos.common.archive;

import com.qualityos.common.exception.BusinessRuleException;
import java.util.List;
import java.util.UUID;

/**
 * Lets a module that uses master data refuse archiving it while it is still in use. Production, for one, refuses to
 * let a product go while it has open work orders. The module that owns the record asks every guard before archiving.
 */
public interface ArchiveGuard {

    enum Kind { PRODUCT, MATERIAL, FACILITY }

    /** What still uses the record, such as "2 open work orders"; empty when nothing does. */
    List<String> usesOf(Kind kind, UUID id);

    /**
     * @param noun how the record is named in the message, such as "product"
     * @throws BusinessRuleException naming every use, when any guard finds one
     */
    static void check(List<ArchiveGuard> guards, Kind kind, UUID id, String noun) {
        List<String> uses = guards.stream().flatMap(guard -> guard.usesOf(kind, id).stream()).toList();
        if (!uses.isEmpty()) {
            throw new BusinessRuleException("This %s is still in use: %s. Finish, cancel or retire these first."
                    .formatted(noun, String.join(", ", uses)));
        }
    }
}
