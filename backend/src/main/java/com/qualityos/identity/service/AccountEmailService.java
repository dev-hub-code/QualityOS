package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.mail.EmailRequest;
import com.qualityos.common.mail.EmailService;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.mail.MailRequested;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.PasswordResetConfirmation;
import com.qualityos.identity.dto.AuthDtos.VerificationStatus;
import com.qualityos.identity.entity.AccountToken;
import com.qualityos.identity.entity.AccountToken.Purpose;
import com.qualityos.identity.entity.User;
import com.qualityos.identity.repository.AccountTokenRepository;
import com.qualityos.identity.repository.RefreshTokenRepository;
import com.qualityos.identity.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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

    /** Stops the reset form being used to fill somebody's inbox. */
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private static final int MAX_VERIFICATION_EMAILS_PER_HOUR = 5;

    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final EmailService emails;
    private final TenantAccessService access;
    private final TransactionTemplate tx;
    private final RegistrationProperties registration;
    private final String appUrl;

    AccountEmailService(
            UserRepository users,
            AccountTokenRepository tokens,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            ApplicationEventPublisher events,
            EmailService emails,
            TenantAccessService access,
            PlatformTransactionManager transactionManager,
            RegistrationProperties registration,
            @Value("${qualityos.app-url}") String appUrl) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.events = events;
        this.emails = emails;
        this.access = access;
        this.tx = new TransactionTemplate(transactionManager);
        this.registration = registration;
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
            mail(user, EmailTemplate.PASSWORD_RESET, "/reset-password?token=" + token, Map.of());
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
            user.markEmailVerified(now);
            refreshTokens.revokeAllForUser(user.getId(), now);
            audit.record("PASSWORD_RESET", "User", user.getId());
            sendPasswordChanged(user);
        }));
    }

    /** Behaves the same for every address, so it cannot be used to discover accounts. */
    public void resendVerification(String email) {
        Optional<User> found = tx.execute(status -> users.findByEmail(AuthService.normalizeEmail(email)));
        if (found.isEmpty()
                || found.get().getEmailVerifiedAt() != null
                || found.get().getStatus() == User.Status.DISABLED) {
            return;
        }
        User user = found.get();
        TenantContext.runAs(null, user.getId(), () -> tx.executeWithoutResult(status -> {
            Instant anHourAgo = Instant.now().minus(Duration.ofHours(1));
            if (!recentlySent(user.getId(), Purpose.EMAIL_VERIFICATION)
                    && tokens.countByUserIdAndPurposeAndCreatedAtAfter(user.getId(), Purpose.EMAIL_VERIFICATION, anHourAgo)
                            < MAX_VERIFICATION_EMAILS_PER_HOUR) {
                sendVerification(user);
            }
        }));
    }

    public void verifyEmail(String rawToken) {
        AccountToken token = find(rawToken, Purpose.EMAIL_VERIFICATION);
        TenantContext.runAs(null, token.getUserId(), () -> tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            consume(token, now);
            User user = users.findById(token.getUserId()).orElseThrow();
            boolean firstTime = user.getStatus() == User.Status.PENDING_VERIFICATION;
            user.markEmailVerified(now);
            tokens.invalidateOutstanding(token.getUserId(), Purpose.EMAIL_VERIFICATION, now);
            audit.record("EMAIL_VERIFIED", "User", token.getUserId());
            if (firstTime) {
                mail(user, EmailTemplate.WELCOME, "/login", Map.of());
            }
        }));
    }

    public VerificationStatus verificationStatus() {
        User user = tx.execute(status -> users.findById(TenantContext.getCurrentUserId())).orElseThrow();
        return new VerificationStatus(user.getEmailVerifiedAt() != null, registration.emailVerificationRequired());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void sendVerification(User user) {
        String token = issue(user.getId(), Purpose.EMAIL_VERIFICATION, registration.verificationTtl());
        audit.record("EMAIL_VERIFICATION_SENT", "User", user.getId());
        mail(user, EmailTemplate.VERIFICATION, "/verify-email?token=" + token,
                Map.of("expiryMinutes", registration.verificationTokenExpiryMinutes()));
    }

    /** Runs in the caller's transaction, so nothing is said about a change that did not happen. */
    @Transactional(propagation = Propagation.MANDATORY)
    void sendPasswordChanged(User user) {
        mail(user, EmailTemplate.PASSWORD_CHANGED, "/forgot-password", Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void sendAccountLocked(User user, Duration lock) {
        mail(user, EmailTemplate.SECURITY_ALERT, "/forgot-password", Map.of("lockMinutes", lock.toMinutes()));
    }

    private void mail(User user, EmailTemplate template, String path, Map<String, Object> details) {
        Map<String, Object> variables = new HashMap<>(details);
        variables.put("recipientName", user.getFullName());
        variables.put("actionUrl", appUrl + path);
        String language = emails.language(
                user.getPreferredLanguage(), access.firstOrganizationLocale(user.getId()).orElse(null));
        events.publishEvent(new MailRequested(new EmailRequest(null, user.getEmail(), template, language, variables)));
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

    private static BusinessRuleException invalidLink() {
        return new BusinessRuleException("This link is not valid or has expired. Please ask for a new one.");
    }
}
