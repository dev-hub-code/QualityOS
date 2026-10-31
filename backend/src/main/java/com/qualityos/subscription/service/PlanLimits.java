package com.qualityos.subscription.service;

import com.qualityos.common.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Takes the subscription row's lock, so that two requests at once cannot both take the last place. */
@Component
public class PlanLimits {

    static final String MEMBERS = """
            (SELECT count(*) FROM tenant_memberships WHERE status = 'ACTIVE')
            + (SELECT count(*) FROM invitations WHERE status = 'PENDING' AND expires_at > now())
            """;
    static final String FACILITIES = "(SELECT count(*) FROM facilities WHERE archived_at IS NULL)";

    private static final String LIMITS = """
            SELECT p.max_users, p.max_facilities, p.name
            FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            FOR UPDATE OF s
            """;

    private record Limits(Integer users, Integer facilities, String plan) {
    }

    private final JdbcTemplate jdbc;

    PlanLimits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUserPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + MEMBERS, Long.class);
        if (limits.users() != null && used >= limits.users()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.users()
                    + " users, counting pending invitations. Change plan to add more.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFacilityPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + FACILITIES, Long.class);
        if (limits.facilities() != null && used >= limits.facilities()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.facilities()
                    + " facilities. Change plan to add more.");
        }
    }

    private Limits current() {
        return jdbc.query(LIMITS, rows -> {
            if (!rows.next()) {
                throw new BusinessRuleException("This organisation has no subscription.");
            }
            return new Limits(rows.getObject("max_users", Integer.class),
                    rows.getObject("max_facilities", Integer.class), rows.getString("name"));
        });
    }
}
