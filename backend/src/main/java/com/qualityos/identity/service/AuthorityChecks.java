package com.qualityos.identity.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.util.Collection;
import java.util.Set;

final class AuthorityChecks {

    private AuthorityChecks() {
    }

    /**
     * Stops an administrator granting permissions they do not hold themselves.
     *
     * @param held what their roles give them, before the plan narrows it, or an owner on a smaller plan could not
     *     invite anyone whose role reaches further than that plan
     */
    static void requireHolds(Collection<String> permissions, Set<String> held) {
        if (!held.containsAll(permissions)) {
            throw new QualityOsException(ErrorCode.FORBIDDEN, "You cannot grant permissions you do not hold.");
        }
    }
}
