package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailService;
import com.qualityos.common.security.JwtProperties;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import com.qualityos.identity.dto.AuthDtos.ChangePasswordRequest;
import com.qualityos.identity.dto.AuthDtos.LoginRequest;
import com.qualityos.identity.dto.AuthDtos.MeResponse;
import com.qualityos.identity.dto.AuthDtos.OrganizationSummary;
import com.qualityos.identity.dto.AuthDtos.PendingVerificationSession;
import com.qualityos.identity.dto.AuthDtos.RegisterRequest;
import com.qualityos.identity.dto.AuthDtos.TokenResponse;
import com.qualityos.identity.dto.AuthDtos.UserSummary;
import com.qualityos.identity.entity.RefreshToken;
import com.qualityos.identity.entity.User;
import com.qualityos.identity.repository.RefreshTokenRepository;
import com.qualityos.identity.repository.UserRepository;
import com.qualityos.organization.service.OrganizationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactions are started explicitly because each flow moves between tenant contexts, and a context is bound
 * to the database when its transaction begins.
 */
@Service
public class AuthService {

    private static final int MAX_FAILED_LOGINS = 10;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /**
     * A web page can refresh from several requests at once. Within this window, a repeated token is treated as
     * that race rather than as theft.
     */
    private static final Duration REUSE_LEEWAY = Duration.ofSeconds(10);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TenantAccessService access;
    private final OrganizationProvisioningService provisioning;
    private final OrganizationService organizations;
    private final AccountEmailService accountEmails;
    private final RegistrationProperties registration;
    private final EmailService emails;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final JwtProperties jwtProperties;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final String unusablePasswordHash;

    AuthService(
            JdbcTemplate jdbc,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            TenantAccessService access,
            OrganizationProvisioningService provisioning,
            OrganizationService organizations,
            AccountEmailService accountEmails,
            RegistrationProperties registration,
            EmailService emails,
            AccessTokenIssuer accessTokens,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            JwtProperties jwtProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.access = access;
        this.provisioning = provisioning;
        this.organizations = organizations;
        this.accountEmails = accountEmails;
        this.registration = registration;
        this.emails = emails;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.jwtProperties = jwtProperties;
        this.tx = new TransactionTemplate(transactionManager);
        this.unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Empty when the account must confirm its email address first, in which case no session exists yet. */
    public Optional<TokenResponse> register(RegisterRequest request) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT registration_open FROM platform_settings", Boolean.class))) {
            throw new BusinessRuleException("New sign-ups are closed at the moment. Please try again later.");
        }
        String email = normalizeEmail(request.email());
        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(email, passwordHash, request.fullName().trim());
        UUID tenantId = UuidV7.generate();
        if (registration.blocksUnverified()) {
            user.setStatus(User.Status.PENDING_VERIFICATION);
            registerAwaitingVerification(user, tenantId, request.organizationName().trim());
            return Optional.empty();
        }

        return Optional.of(TenantContext.callAs(tenantId, user.getId(), () -> tx.execute(status -> {
            if (users.findByEmail(email).isPresent()) {
                throw new ConflictException("An account with this email already exists.");
            }
            provision(user, tenantId, request.organizationName().trim());
            return issueSession(user.getId(), tenantId);
        })));
    }

    /** Answers the same for a taken address, so public sign-up cannot be used to discover accounts. */
    private void registerAwaitingVerification(User user, UUID tenantId, String organizationName) {
        boolean created;
        try {
            created = TenantContext.callAs(tenantId, user.getId(), () -> tx.execute(status -> {
                if (users.findByEmail(user.getEmail()).isPresent()) {
                    return false;
                }
                provision(user, tenantId, organizationName);
                return true;
            }));
        } catch (DataIntegrityViolationException raced) {
            created = false;
        }
        if (!created) {
            accountEmails.resendVerification(user.getEmail());
        }
    }

    private void provision(User user, UUID tenantId, String organizationName) {
        users.saveAndFlush(user);
        provisioning.provision(tenantId, user.getId(), organizationName);
        audit.record("USER_REGISTERED", "User", user.getId());
        accountEmails.sendVerification(user);
    }

    /** Holds a full session, or else a session restricted to an account that is still pending. */
    public record LoginResult(TokenResponse session, PendingVerificationSession pending) {
    }

    public LoginResult login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        Optional<User> found = tx.execute(status -> users.findByEmail(email));
        if (found.isEmpty()) {
            // Spends the same time as a real check, so timing does not reveal which emails have accounts.
            passwordEncoder.matches(request.password(), unusablePasswordHash);
            throw invalidCredentials();
        }
        User user = found.get();
        checkPassword(user, request.password());
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            TenantContext.runAs(null, user.getId(), () -> tx.executeWithoutResult(status ->
                    audit.record("PENDING_VERIFICATION_LOGIN", "User", user.getId())));
            return new LoginResult(null, new PendingVerificationSession(
                    accessTokens.issuePendingVerification(user.getId()), accessTokens.ttlSeconds(), true));
        }

        UUID tenantId = chooseTenant(user.getId(), request.tenantId());
        return new LoginResult(TenantContext.callAs(tenantId, user.getId(), () -> tx.execute(status -> {
            User current = users.findById(user.getId()).orElseThrow();
            current.setFailedLoginCount(0);
            current.setLockedUntil(null);
            current.setLastLoginAt(Instant.now());
            audit.record("USER_LOGGED_IN", "User", user.getId());
            return issueSession(user.getId(), tenantId);
        })), null);
    }

    /** Must run with no open transaction. Applies the same lockout rules wherever a password is presented. */
    void requirePassword(User user, String password) {
        checkPassword(user, password);
        // Said only after the right password, so that it reveals nothing to anyone else.
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            throw new QualityOsException(ErrorCode.EMAIL_NOT_VERIFIED, "Please confirm your email address first.");
        }
    }

    /**
     * Asks the signed-in person for their password again, under the same lockout as signing in. It answers with its
     * own code because 401 means the session has ended, and this session has not.
     */
    public void confirmPassword(String password) {
        User user = tx.execute(status -> users.findById(TenantContext.getCurrentUserId()).orElseThrow());
        try {
            checkPassword(user, password);
        } catch (QualityOsException ex) {
            throw new QualityOsException(ErrorCode.REAUTHENTICATION_FAILED, "That password is not right.");
        }
    }

    private void checkPassword(User user, String password) {
        boolean passwordMatches = passwordEncoder.matches(password, user.getPasswordHash());
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now());
        if (locked || !passwordMatches || user.getStatus() == User.Status.DISABLED) {
            if (!locked && !passwordMatches) {
                recordFailedLogin(user.getId());
            }
            throw invalidCredentials();
        }
    }

    public TokenResponse refresh(String presentedToken) {
        Rotation rotation = tx.execute(status -> rotate(presentedToken));
        if (rotation.replacement == null) {
            throw invalidRefreshToken();
        }
        if (access.resolvePermissions(rotation.tenantId, rotation.userId).isEmpty()) {
            tx.executeWithoutResult(status -> refreshTokens.revokeFamily(rotation.familyId, Instant.now()));
            throw invalidRefreshToken();
        }
        return new TokenResponse(
                accessTokens.issue(rotation.userId, rotation.tenantId),
                rotation.replacement,
                accessTokens.ttlSeconds(),
                rotation.tenantId);
    }

    private Rotation rotate(String presentedToken) {
        Optional<RefreshToken> found = refreshTokens.findByHashForUpdate(RefreshTokens.hash(presentedToken));
        Instant now = Instant.now();
        if (found.isEmpty()) {
            return Rotation.rejected();
        }
        RefreshToken token = found.get();
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(now)) {
            return Rotation.rejected();
        }
        if (token.getConsumedAt() != null && token.getConsumedAt().plus(REUSE_LEEWAY).isBefore(now)) {
            // An old token came back: either it or its successor is in the wrong hands, so end the session.
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            return Rotation.rejected();
        }
        if (token.getConsumedAt() == null) {
            token.setConsumedAt(now);
        }
        String replacement = RefreshTokens.generate();
        refreshTokens.save(newToken(token.getUserId(), token.getTenantId(), token.getFamilyId(), replacement));
        return new Rotation(token.getUserId(), token.getTenantId(), token.getFamilyId(), replacement);
    }

    public void logout(String presentedToken) {
        tx.executeWithoutResult(status -> refreshTokens.findByTokenHash(RefreshTokens.hash(presentedToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), Instant.now())));
    }

    /** Membership is verified here; the requested id only selects among the user's own organisations. */
    public TokenResponse switchTenant(UUID targetTenantId, String presentedToken) {
        UUID userId = TenantContext.getCurrentUserId();
        if (access.resolvePermissions(targetTenantId, userId).isEmpty()) {
            throw new ResourceNotFoundException("Organization", targetTenantId);
        }
        logout(presentedToken);
        return TenantContext.callAs(targetTenantId, userId, () -> tx.execute(status -> {
            audit.record("TENANT_SWITCHED", "Organization", targetTenantId);
            return issueSession(userId, targetTenantId);
        }));
    }

    public TokenResponse createOrganization(String name) {
        UUID userId = TenantContext.getCurrentUserId();
        UUID tenantId = UuidV7.generate();
        return TenantContext.callAs(tenantId, userId, () -> tx.execute(status -> {
            provisioning.provision(tenantId, userId, name.trim());
            return issueSession(userId, tenantId);
        }));
    }

    public void changePassword(ChangePasswordRequest request) {
        UUID userId = TenantContext.getCurrentUserId();
        User user = tx.execute(status -> users.findById(userId).orElseThrow());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        String newHash = passwordEncoder.encode(request.newPassword());
        tx.executeWithoutResult(status -> {
            User current = users.findById(userId).orElseThrow();
            current.setPasswordHash(newHash);
            refreshTokens.revokeAllForUser(userId, Instant.now());
            audit.record("PASSWORD_CHANGED", "User", userId);
            accountEmails.sendPasswordChanged(current);
        });
    }

    /** @param language empty to follow the organisation's default */
    public void changeEmailLanguage(String language) {
        if (language != null && !emails.supports(language)) {
            throw new BusinessRuleException("That language is not available.");
        }
        UUID userId = TenantContext.getCurrentUserId();
        tx.executeWithoutResult(status -> {
            users.findById(userId).orElseThrow().setPreferredLanguage(language);
            audit.record("EMAIL_LANGUAGE_CHANGED", "User", userId);
        });
    }

    public MeResponse me(Set<String> permissions) {
        UUID userId = TenantContext.getCurrentUserId();
        UUID tenantId = TenantContext.getCurrentTenantId();
        User user = tx.execute(status -> users.findById(userId).orElseThrow());
        List<OrganizationSummary> memberOf = access.organizationsOf(userId);
        boolean hasSubscription = Boolean.TRUE.equals(tx.execute(status -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM subscriptions WHERE tenant_id = ?)", Boolean.class, tenantId)));
        return new MeResponse(
                new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getEmailVerifiedAt() != null,
                        user.getPreferredLanguage()),
                tenantId,
                organizations.getCurrent().onboarded(),
                hasSubscription,
                permissions,
                memberOf);
    }

    private UUID chooseTenant(UUID userId, UUID preferred) {
        List<OrganizationSummary> memberOf = access.organizationsOf(userId);
        if (memberOf.isEmpty()) {
            throw new QualityOsException(
                    ErrorCode.NO_ACTIVE_MEMBERSHIP, "This account does not belong to an active organisation.");
        }
        return memberOf.stream()
                .map(OrganizationSummary::id)
                .filter(id -> id.equals(preferred))
                .findFirst()
                .orElse(memberOf.getFirst().id());
    }

    private void recordFailedLogin(UUID userId) {
        TenantContext.runAs(null, userId, () -> tx.executeWithoutResult(status -> {
            User user = users.findById(userId).orElseThrow();
            int failures = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failures);
            if (failures >= MAX_FAILED_LOGINS) {
                user.setFailedLoginCount(0);
                user.setLockedUntil(Instant.now().plus(LOCK_DURATION));
                audit.record("USER_LOCKED", "User", userId);
                accountEmails.sendAccountLocked(user, LOCK_DURATION);
            }
            audit.record("LOGIN_FAILED", "User", userId);
        }));
    }

    TokenResponse issueSession(UUID userId, UUID tenantId) {
        String refreshToken = RefreshTokens.generate();
        refreshTokens.save(newToken(userId, tenantId, UuidV7.generate(), refreshToken));
        return new TokenResponse(
                accessTokens.issue(userId, tenantId), refreshToken, accessTokens.ttlSeconds(), tenantId);
    }

    private RefreshToken newToken(UUID userId, UUID tenantId, UUID familyId, String rawToken) {
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());
        return new RefreshToken(userId, tenantId, familyId, RefreshTokens.hash(rawToken), expiresAt);
    }

    private static QualityOsException invalidCredentials() {
        return new QualityOsException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
    }

    private static QualityOsException invalidRefreshToken() {
        return new QualityOsException(ErrorCode.INVALID_REFRESH_TOKEN, "Please sign in again.");
    }

    private record Rotation(UUID userId, UUID tenantId, UUID familyId, String replacement) {

        static Rotation rejected() {
            return new Rotation(null, null, null, null);
        }
    }
}
