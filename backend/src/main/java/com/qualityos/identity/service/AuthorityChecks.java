package com.qualityos.identity.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

final class AuthorityChecks {

    private AuthorityChecks() {
    }

    /** Stops an administrator granting permissions they do not hold themselves. */
    static void requireHolds(Collection<String> permissions) {
        Set<String> held = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (!held.containsAll(permissions)) {
            throw new QualityOsException(ErrorCode.FORBIDDEN, "You cannot grant permissions you do not hold.");
        }
    }
}
