package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.security.SecretBox;
import com.qualityos.common.security.Totp;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.repository.UserRepository;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An operator's authenticator app. The secret lives sealed in the database, behind functions that only answer for
 * the operator themselves, and a code is accepted once. Nothing here applies to an ordinary member.
 */
@Service
public class OperatorSecondFactor {

    public static final String ISSUER = "ProductionOS";

    public enum State { NONE, PENDING, ENROLLED }

    public record Enrolment(String secret, String otpauthUri) {
    }

    private final JdbcTemplate jdbc;
    private final SecretBox secrets;
    private final UserRepository users;
    private final AuditService audit;
    private final AccountEmailService accountEmails;
    private final TransactionTemplate tx;

    OperatorSecondFactor(
            JdbcTemplate jdbc,
            SecretBox secrets,
            UserRepository users,
            AuditService audit,
            AccountEmailService accountEmails,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.users = users;
        this.audit = audit;
        this.accountEmails = accountEmails;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** For the signed-in operator. */
    public State state() {
        return tx.execute(status -> State.valueOf(
                jdbc.queryForObject("SELECT platform_second_factor_state()", String.class)));
    }

    public boolean enrolled() {
        return state() == State.ENROLLED;
    }

    /** A new secret, shown once. Starting again before confirming replaces the secret not yet confirmed. */
    public Enrolment begin() {
        return tx.execute(status -> {
            if (jdbc.queryForObject("SELECT platform_second_factor_state()", String.class).equals("ENROLLED")) {
                throw new ConflictException("An authenticator is already set up. A Super Admin can reset it.");
            }
            byte[] secret = Totp.newSecret();
            jdbc.queryForObject("SELECT platform_second_factor_begin(?)::text", String.class, secrets.seal(secret));
            String email = users.findById(TenantContext.getCurrentUserId()).orElseThrow().getEmail();
            return new Enrolment(Totp.base32(secret), Totp.otpauthUri(ISSUER, email, secret));
        });
    }

    /** The first code from the app proves it holds the secret. From here on, signing in asks for one. */
    public void confirm(String code) {
        tx.executeWithoutResult(status -> {
            byte[] sealed = jdbc.queryForObject("SELECT platform_second_factor_pending()", byte[].class);
            if (sealed == null) {
                throw new BusinessRuleException("Start by adding the account to your authenticator app.");
            }
            OptionalLong step = Totp.matchingStep(secrets.open(sealed), code, Instant.now());
            if (step.isEmpty()) {
                throw new QualityOsException(ErrorCode.SECOND_FACTOR_FAILED,
                        "That code is not right. Codes change every 30 seconds, so try the current one.");
            }
            jdbc.queryForObject("SELECT platform_second_factor_confirm(?)", Boolean.class, step.getAsLong());
            UUID userId = TenantContext.getCurrentUserId();
            audit.record("SECOND_FACTOR_ENROLLED", "PlatformOperator", userId);
            accountEmails.sendSecondFactorEnrolled(userId);
        });
    }

    /** Whether signing in as this account needs a code: true for an active operator who has enrolled. */
    public boolean required(UUID userId) {
        return sealedForLogin(userId) != null;
    }

    /** Spends the code. False for a wrong code, a code already used, or an account that needs none. */
    public boolean verifyAtLogin(UUID userId, String code) {
        byte[] sealed = sealedForLogin(userId);
        if (sealed == null) {
            return false;
        }
        OptionalLong step = Totp.matchingStep(secrets.open(sealed), code, Instant.now());
        return step.isPresent() && Boolean.TRUE.equals(TenantContext.callAs(null, userId, () -> tx.execute(status ->
                jdbc.queryForObject("SELECT platform_second_factor_use(?, ?)", Boolean.class, userId,
                        step.getAsLong()))));
    }

    private byte[] sealedForLogin(UUID userId) {
        return TenantContext.callAs(null, userId, () -> tx.execute(status ->
                jdbc.queryForObject("SELECT platform_second_factor_for_login(?)", byte[].class, userId)));
    }
}
