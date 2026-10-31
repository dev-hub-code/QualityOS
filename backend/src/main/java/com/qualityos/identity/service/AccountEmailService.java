package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.mail.MailMessage;
import com.qualityos.common.mail.MailRequested;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.PasswordResetConfirmation;
import com.qualityos.identity.entity.AccountToken;
import com.qualityos.identity.entity.AccountToken.Purpose;
import com.qualityos.identity.entity.User;
import com.qualityos.identity.repository.AccountTokenRepository;
import com.qualityos.identity.repository.RefreshTokenRepository;
import com.qualityos.identity.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Password reset and email verification, by one-time link. */
@Service
public class AccountEmailService {

    private static final Duration RESET_TTL = Duration.ofHours(1);
    private static final Duration VERIFICATION_TTL = Duration.ofDays(3);

    /** Stops the reset form being used to fill somebody's inbox. */
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final String appUrl;

    AccountEmailService(
            UserRepository users,
            AccountTokenRepository tokens,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.app-url}") String appUrl) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.appUrl = appUrl;
    }

    /** Behaves the same whether or not the address has an account, so it cannot be used to discover accounts. */
    public void requestPasswordReset(String email) {
        Optional<User> found = tx.execute(status -> users.findByEmail(AuthService.normalizeEmail(email)));
        if (found.isEmpty() || found.get().getStatus() != User.Status.ACTIVE) {
            return;
        }
        User user = found.get();
        TenantContext.runAs(null, user.getId(), () -> tx.executeWithoutResult(status -> {
            if (recentlySent(user.getId(), Purpose.PASSWORD_RESET)) {
                return;
            }
            String token = issue(user.getId(), Purpose.PASSWORD_RESET, RESET_TTL);
            events.publishEvent(new MailRequested(new MailMessage(
                    user.getEmail(),
                    "Reset your QualityOS password",
                    """
                    Someone asked to reset the password for this QualityOS account.

                    Use this link within one hour:
                    %s/reset-password?token=%s

                    If this was not you, ignore this email. Your password has not changed.
                    """.formatted(appUrl, token))));
            audit.record("PASSWORD_RESET_REQUESTED", "User", user.getId());
        }));
    }

    public void resetPassword(PasswordResetConfirmation request) {
        AccountToken token = find(request.token(), Purpose.PASSWORD_RESET);
        String newHash = passwordEncoder.encode(request.newPassword());
        TenantContext.runAs(null, token.getUserId(), () -> tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            consume(token, now);
            User user = users.findById(token.getUserId()).orElseThrow();
            user.setPasswordHash(newHash);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            // The link reached this mailbox, which is the same proof that verification asks for.
            markVerified(user, now);
            refreshTokens.revokeAllForUser(user.getId(), now);
            audit.record("PASSWORD_RESET", "User", user.getId());
        }));
    }

    public void resendVerification() {
        UUID userId = TenantContext.getCurrentUserId();
        tx.executeWithoutResult(status -> {
            User user = users.findById(userId).orElseThrow();
            if (user.getEmailVerifiedAt() == null && !recentlySent(userId, Purpose.EMAIL_VERIFICATION)) {
                sendVerification(user);
            }
        });
    }

    public void verifyEmail(String rawToken) {
        AccountToken token = find(rawToken, Purpose.EMAIL_VERIFICATION);
        TenantContext.runAs(null, token.getUserId(), () -> tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            consume(token, now);
            markVerified(users.findById(token.getUserId()).orElseThrow(), now);
            audit.record("EMAIL_VERIFIED", "User", token.getUserId());
        }));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void sendVerification(User user) {
        String token = issue(user.getId(), Purpose.EMAIL_VERIFICATION, VERIFICATION_TTL);
        events.publishEvent(new MailRequested(new MailMessage(
                user.getEmail(),
                "Confirm your QualityOS email address",
                """
                Please confirm this email address for your QualityOS account:
                %s/verify-email?token=%s

                The link works for three days.
                """.formatted(appUrl, token))));
    }

    private String issue(UUID userId, Purpose purpose, Duration ttl) {
        Instant now = Instant.now();
        tokens.invalidateOutstanding(userId, purpose, now);
        String raw = RefreshTokens.generate();
        tokens.save(new AccountToken(userId, purpose, RefreshTokens.hash(raw), now.plus(ttl)));
        return raw;
    }

    private boolean recentlySent(UUID userId, Purpose purpose) {
        return tokens.existsByUserIdAndPurposeAndCreatedAtAfter(userId, purpose, Instant.now().minus(RESEND_COOLDOWN));
    }

    private AccountToken find(String rawToken, Purpose purpose) {
        return tx.execute(status -> tokens.findByTokenHashAndPurpose(RefreshTokens.hash(rawToken), purpose))
                .orElseThrow(AccountEmailService::invalidLink);
    }

    private void consume(AccountToken token, Instant now) {
        if (tokens.consume(token.getId(), now) != 1) {
            throw invalidLink();
        }
    }

    private static void markVerified(User user, Instant now) {
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
        }
    }

    private static BusinessRuleException invalidLink() {
        return new BusinessRuleException("This link is not valid or has expired. Please ask for a new one.");
    }
}
