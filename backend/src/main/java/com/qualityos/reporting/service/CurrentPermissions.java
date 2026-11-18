package com.qualityos.reporting.service;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The permissions of the person making the request. */
final class CurrentPermissions {

    private CurrentPermissions() {
    }

    static Set<String> held() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
    }
}
