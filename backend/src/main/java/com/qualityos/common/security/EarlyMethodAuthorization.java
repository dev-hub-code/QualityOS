package com.qualityos.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Checks a controller's {@code @PreAuthorize} before its arguments are read. Method security alone runs once the body
 * is bound and validated, so someone without the permission sending a malformed body was told which fields were wrong
 * (400) rather than that they may not do this (403). The check is the same one method security repeats afterwards; no
 * expression here reads the method's arguments, which are not known yet.
 */
@Component
class EarlyMethodAuthorization implements HandlerInterceptor {

    private static final Object[] NO_ARGUMENTS = new Object[0];

    private final PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !hasBody(request)) {
            return true;
        }
        var invocation = new SimpleMethodInvocation(method.getBean(), method.getMethod(), NO_ARGUMENTS);
        AuthorizationResult result = manager.authorize(
                () -> SecurityContextHolder.getContext().getAuthentication(), invocation);
        if (result != null && !result.isGranted()) {
            throw new AccessDeniedException("Access denied");
        }
        return true;
    }

    private static boolean hasBody(HttpServletRequest request) {
        String verb = request.getMethod();
        return "POST".equals(verb) || "PUT".equals(verb) || "PATCH".equals(verb);
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {

        private final EarlyMethodAuthorization check;

        Registration(EarlyMethodAuthorization check) {
            this.check = check;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(check).addPathPatterns("/api/**");
        }
    }
}
