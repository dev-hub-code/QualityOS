package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** A new endpoint must name its permission, or be added here with a reason it needs none. */
class EndpointAuthorizationIT extends AbstractIntegrationTest {

    private static final Set<String> NEEDS_NO_PERMISSION = Set.of(
            // Before sign-in, or about the caller's own session.
            "GET /api/v1/auth/me",
            "GET /api/v1/auth/verification-status",
            "POST /api/v1/auth/change-password",
            "POST /api/v1/auth/invitations/accept",
            "POST /api/v1/auth/invitations/preview",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/password-reset/confirm",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/resend-verification",
            "POST /api/v1/auth/switch-tenant",
            "POST /api/v1/auth/verify-email",
            "PUT /api/v1/auth/me/email-language",
            // Open to everyone, and hold no organisation data.
            "GET /api/v1/public/plans",
            "GET /api/v1/system/info",
            // The caller's own notifications.
            "GET /api/v1/notifications",
            "GET /api/v1/notifications/preferences",
            "GET /api/v1/notifications/unread-count",
            "POST /api/v1/notifications/{id}/read",
            "POST /api/v1/notifications/read-all",
            "PUT /api/v1/notifications/preferences",
            // The parent record decides, in AttachmentService.
            "DELETE /api/v1/attachments/{id}",
            "GET /api/v1/attachments",
            "GET /api/v1/attachments/{id}/content",
            "POST /api/v1/attachments",
            // The caller's own organisations.
            "GET /api/v1/organizations",
            "GET /api/v1/organizations/{id}",
            "POST /api/v1/organizations");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointNamesAPermission() {
        Set<String> unguarded = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getName().startsWith("com.qualityos") && !guarded(handler)) {
                unguarded.add(describe(info));
            }
        });
        assertThat(unguarded).isEqualTo(new TreeSet<>(NEEDS_NO_PERMISSION));
    }

    private static boolean guarded(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    private static String describe(RequestMappingInfo info) {
        return info.getMethodsCondition().getMethods().iterator().next() + " "
                + info.getPatternValues().iterator().next();
    }
}
