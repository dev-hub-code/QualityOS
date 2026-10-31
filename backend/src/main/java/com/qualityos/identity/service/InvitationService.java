package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailRequest;
import com.qualityos.common.mail.EmailService;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.mail.MailRequested;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.AcceptInvitationRequest;
import com.qualityos.identity.dto.AuthDtos.InvitationPreview;
import com.qualityos.identity.dto.AuthDtos.TokenResponse;
import com.qualityos.identity.dto.UserRoleDtos.InvitationResponse;
import com.qualityos.identity.dto.UserRoleDtos.InviteRequest;
import com.qualityos.identity.entity.Invitation;
import com.qualityos.identity.entity.Membership;
import com.qualityos.identity.entity.Role;
import com.qualityos.identity.entity.User;
import com.qualityos.identity.entity.UserRole;
import com.qualityos.identity.repository.InvitationRepository;
import com.qualityos.identity.repository.MembershipRepository;
import com.qualityos.identity.repository.RoleRepository;
import com.qualityos.identity.repository.UserRepository;
import com.qualityos.identity.repository.UserRoleRepository;
import com.qualityos.organization.service.OrganizationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.qualityos.subscription.service.PlanLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Joining an organisation by email link. An invitation token carries its tenant id so that it can be looked up
 * under that tenant's row-level security. The id is not trusted: the token only works if its hash is found there.
 */
@Service
public class InvitationService {

    private static final Duration TTL = Duration.ofDays(7);
    private static final char SEPARATOR = '.';
    private static final int MIN_PASSWORD = 10;

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final UserManagementService userManagement;
    private final AuthService auth;
    private final OrganizationService organizations;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final PlanLimits limits;
    private final ApplicationEventPublisher events;
    private final EmailService emails;
    private final TransactionTemplate tx;
    private final String appUrl;

    InvitationService(
            InvitationRepository invitations,
            UserRepository users,
            MembershipRepository memberships,
            RoleRepository roles,
            UserRoleRepository userRoles,
            UserManagementService userManagement,
            AuthService auth,
            OrganizationService organizations,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            PlanLimits limits,
            ApplicationEventPublisher events,
            EmailService emails,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.app-url}") String appUrl) {
        this.invitations = invitations;
        this.limits = limits;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.userRoles = userRoles;
        this.userManagement = userManagement;
        this.auth = auth;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.events = events;
        this.emails = emails;
        this.tx = new TransactionTemplate(transactionManager);
        this.appUrl = appUrl;
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listPending() {
        return invitations.findByStatusOrderByCreatedAtDesc(Invitation.Status.PENDING).stream()
                .map(InvitationService::toResponse)
                .toList();
    }

    @Transactional
    public InvitationResponse invite(InviteRequest request) {
        List<Role> granted = userManagement.loadGrantable(request.roleIds());
        String email = AuthService.normalizeEmail(request.email());
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent() && memberships.findByUserId(existing.get().getId()).isPresent()) {
            throw new ConflictException("This user is already a member of the organisation.");
        }
        invitations.findByEmailAndStatus(email, Invitation.Status.PENDING).ifPresent(previous -> {
            if (previous.isUsable(Instant.now())) {
                throw new ConflictException("This address already has a pending invitation.");
            }
            previous.revoke();
            invitations.saveAndFlush(previous);
        });

        limits.requireUserPlace();
        String secret = RefreshTokens.generate();
        Invitation invitation = invitations.saveAndFlush(new Invitation(
                email, granted.stream().map(Role::getId).toList(), RefreshTokens.hash(secret), expiry()));
        audit.record("MEMBER_INVITED", "Invitation", invitation.getId(), null,
                Map.of("roles", granted.stream().map(Role::getCode).toList()));
        sendEmail(invitation, secret);
        return toResponse(invitation);
    }

    @Transactional
    public InvitationResponse resend(UUID id) {
        Invitation invitation = loadPending(id);
        String secret = RefreshTokens.generate();
        invitation.renew(RefreshTokens.hash(secret), expiry());
        audit.record("INVITATION_RESENT", "Invitation", id);
        sendEmail(invitation, secret);
        return toResponse(invitation);
    }

    @Transactional
    public void revoke(UUID id) {
        loadPending(id).revoke();
        audit.record("INVITATION_REVOKED", "Invitation", id);
    }

    public InvitationPreview preview(String token) {
        UUID tenantId = tenantOf(token);
        return TenantContext.callAs(tenantId, null, () -> tx.execute(status -> {
            Invitation invitation = findUsable(token);
            return new InvitationPreview(
                    organizations.getCurrent().name(),
                    invitation.getEmail(),
                    users.findByEmail(invitation.getEmail()).isPresent());
        }));
    }

    /** A new account takes the password given here. An existing account must prove itself with its own. */
    public TokenResponse accept(AcceptInvitationRequest request) {
        UUID tenantId = tenantOf(request.token());
        String email = TenantContext.callAs(tenantId, null, () -> tx.execute(status ->
                findUsable(request.token()).getEmail()));
        Optional<User> existing = tx.execute(status -> users.findByEmail(email));

        User user;
        if (existing.isPresent()) {
            user = existing.get();
            auth.requirePassword(user, request.password());
        } else {
            user = newUser(email, request);
        }

        return TenantContext.callAs(tenantId, user.getId(), () -> tx.execute(status -> {
            Instant now = Instant.now();
            Invitation invitation = invitations.findByHashForUpdate(RefreshTokens.hash(secretOf(request.token())))
                    .filter(found -> found.isUsable(now))
                    .orElseThrow(InvitationService::invalidInvitation);
            if (memberships.findByUserId(user.getId()).isPresent()) {
                throw new ConflictException("You are already a member of this organisation.");
            }
            List<Role> granted = roles.findVisibleByIds(invitation.getRoleIds(), tenantId);
            if (granted.size() != invitation.getRoleIds().size()) {
                throw new BusinessRuleException("A role on this invitation no longer exists. Please ask for a new one.");
            }
            User account = existing.isPresent() ? users.findById(user.getId()).orElseThrow() : users.saveAndFlush(user);
            // Reading the invitation proves this mailbox.
            account.markEmailVerified(now);
            Membership membership = memberships.saveAndFlush(new Membership(account.getId()));
            granted.forEach(role -> userRoles.save(new UserRole(membership.getId(), role.getId())));
            invitation.accept(now);
            audit.record("MEMBER_JOINED", "Membership", membership.getId(), null,
                    Map.of("roles", granted.stream().map(Role::getCode).toList()));
            return auth.issueSession(account.getId(), tenantId);
        }));
    }

    private User newUser(String email, AcceptInvitationRequest request) {
        if (request.fullName() == null || request.fullName().isBlank()) {
            throw new BusinessRuleException("Enter your name.");
        }
        if (request.password().length() < MIN_PASSWORD) {
            throw new BusinessRuleException("Choose a password of at least " + MIN_PASSWORD + " characters.");
        }
        return new User(email, passwordEncoder.encode(request.password()), request.fullName().trim());
    }

    private Invitation findUsable(String token) {
        return invitations.findByTokenHash(RefreshTokens.hash(secretOf(token)))
                .filter(invitation -> invitation.isUsable(Instant.now()))
                .orElseThrow(InvitationService::invalidInvitation);
    }

    private Invitation loadPending(UUID id) {
        return invitations.findById(id)
                .filter(invitation -> invitation.getStatus() == Invitation.Status.PENDING)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", id));
    }

    private void sendEmail(Invitation invitation, String secret) {
        String organization = organizations.getCurrent().name();
        String token = invitation.getTenantId().toString() + SEPARATOR + secret;
        String language = emails.language(null, organizations.getCurrent().locale());
        events.publishEvent(new MailRequested(new EmailRequest(invitation.getTenantId(), invitation.getEmail(),
                EmailTemplate.INVITATION, language, Map.of(
                        "organizationName", organization,
                        "actionUrl", appUrl + "/accept-invitation?token=" + token))));
    }

    private static Instant expiry() {
        return Instant.now().plus(TTL);
    }

    private static UUID tenantOf(String token) {
        int separator = token.indexOf(SEPARATOR);
        if (separator < 0) {
            throw invalidInvitation();
        }
        try {
            return UUID.fromString(token.substring(0, separator));
        } catch (IllegalArgumentException ex) {
            throw invalidInvitation();
        }
    }

    private static String secretOf(String token) {
        return token.substring(token.indexOf(SEPARATOR) + 1);
    }

    private static InvitationResponse toResponse(Invitation invitation) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getExpiresAt(),
                !invitation.isUsable(Instant.now()));
    }

    private static BusinessRuleException invalidInvitation() {
        return new BusinessRuleException("This invitation is not valid or has expired. Please ask for a new one.");
    }
}
