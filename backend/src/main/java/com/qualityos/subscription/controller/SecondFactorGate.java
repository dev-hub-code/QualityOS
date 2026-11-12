package com.qualityos.subscription.controller;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.OperatorSecondFactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Closes the Control Center to an operator who has not set up their authenticator app, apart from the endpoints
 * that set it up. Anyone who is not an operator passes through to be refused by the controllers as before.
 */
@Component
class SecondFactorGate implements HandlerInterceptor {

    static final String PLATFORM = "/api/v1/platform/**";
    static final String ENROLMENT = "/api/v1/platform/second-factor/**";

    /** Looked up when first needed, so that a web slice test without the service can still start. */
    private final ObjectProvider<OperatorSecondFactor> secondFactor;

    SecondFactorGate(ObjectProvider<OperatorSecondFactor> secondFactor) {
        this.secondFactor = secondFactor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean operator = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> Permissions.PLATFORM_ADMIN.equals(granted.getAuthority()));
        if (operator && !secondFactor.getObject().enrolled()) {
            throw new QualityOsException(ErrorCode.SECOND_FACTOR_REQUIRED,
                    "Set up an authenticator app to use the Control Center.");
        }
        return true;
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {

        private final SecondFactorGate gate;

        Registration(SecondFactorGate gate) {
            this.gate = gate;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(gate).addPathPatterns(PLATFORM).excludePathPatterns(ENROLMENT);
        }
    }
}
