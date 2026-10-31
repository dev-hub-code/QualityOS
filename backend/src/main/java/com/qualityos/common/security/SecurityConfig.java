package com.qualityos.common.security;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.response.ApiError;
import com.qualityos.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/password-reset/**", "/api/v1/auth/verify-email",
        "/api/v1/auth/resend-verification", "/api/v1/auth/invitations/**",
        "/api/v1/system/info", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantJwtAuthenticationConverter jwtConverter,
            JsonMapper json,
            @Value("${qualityos.security.auth-rate-limit-per-minute}") int authRateLimit) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, ex) ->
                writeError(response, json, ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        AccessDeniedHandler denied = (request, response, ex) ->
                writeError(response, json, ErrorCode.FORBIDDEN, "You do not have permission to do this.");

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .addFilterBefore(
                        new AuthRateLimitFilter(authRateLimit, json, Clock.systemUTC()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    private static void writeError(HttpServletResponse response, JsonMapper json, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(ApiResponse.failure(new ApiError(code.name(), message))));
    }
}
