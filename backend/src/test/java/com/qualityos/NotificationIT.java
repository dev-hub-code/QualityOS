package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.mail.MailMessage;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.notification.service.NotificationDeliveryService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationIT extends AbstractIntegrationTest {

    private static final String TYPE = "INSPECTION_SENT_BACK";

    @Autowired
    private NotificationService notifications;

    @Autowired
    private NotificationDeliveryService delivery;

    private void notify(Session session, Session... recipients) {
        Set<UUID> ids = java.util.Arrays.stream(recipients).map(Session::userId).collect(java.util.stream.Collectors.toSet());
        TenantContext.runAs(session.tenantId(), null, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> notifications.notify(
                        ids, NotificationType.valueOf(TYPE), "Title", "Body", "/inspections/1")));
    }

    private String emailStatus(Session session) throws Exception {
        String[] value = new String[1];
        asAppRole(session.tenantId(), sql -> value[0] = sql.queryForObject(
                "SELECT email_status FROM notifications WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, session.tenantId()));
        return value[0];
    }

    private void makeDue(Session session) throws Exception {
        asAppRole(session.tenantId(), sql -> sql.update("UPDATE notifications SET email_next_attempt_at = now()"));
    }

    @Test
    void anEmailGoesOutWithAnAbsoluteLinkAndIsNotSentTwice() throws Exception {
        Session user = register();
        notify(user, user);

        delivery.deliverPending();
        delivery.deliverPending();

        assertThat(mail.to(user.email()).stream().filter(m -> m.subject().equals("Title")).toList())
                .singleElement()
                .extracting(MailMessage::text).asString().contains("http://localhost:3000/inspections/1");
        assertThat(emailStatus(user)).isEqualTo("SENT");
    }

    @Test
    void aMailOutageIsRetriedAndEventuallyGivenUp() throws Exception {
        Session user = register();
        notify(user, user);
        mail.setFailing(true);
        try {
            delivery.deliverPending();
            assertThat(emailStatus(user)).isEqualTo("PENDING");
            delivery.deliverPending();
            asAppRole(user.tenantId(), sql -> assertThat(sql.queryForObject(
                    "SELECT email_attempts FROM notifications", Integer.class)).isEqualTo(1));

            for (int attempt = 0; attempt < 5; attempt++) {
                makeDue(user);
                delivery.deliverPending();
            }
            assertThat(emailStatus(user)).isEqualTo("FAILED");
        } finally {
            mail.setFailing(false);
        }
        as(user, get("/api/v1/notifications")).andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void aSuccessfulRetryCompletesDelivery() throws Exception {
        Session user = register();
        notify(user, user);
        mail.setFailing(true);
        try {
            delivery.deliverPending();
        } finally {
            mail.setFailing(false);
        }
        makeDue(user);
        delivery.deliverPending();
        assertThat(emailStatus(user)).isEqualTo("SENT");
    }

    @Test
    void someoneWhoLeftTheOrganisationIsNotEmailed() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        notify(owner, member);
        as(owner, postJson("/api/v1/users/" + membershipId(owner, member.userId()) + "/deactivate", Map.of()))
                .andExpect(status().isNoContent());

        delivery.deliverPending();

        assertThat(emailStatus(owner)).isEqualTo("SKIPPED");
        assertThat(mail.to(member.email()).stream().filter(m -> m.subject().equals("Title"))).isEmpty();
    }

    @Test
    void aUserCanTurnOffEmailButStillSeesTheInAppNotification() throws Exception {
        Session user = register();
        as(user, put("/api/v1/notifications/preferences").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("type", TYPE, "emailEnabled", false))))
                .andExpect(status().isNoContent());
        as(user, get("/api/v1/notifications/preferences"))
                .andExpect(jsonPath("$.data[?(@.type == '" + TYPE + "')].emailEnabled").value(false))
                .andExpect(jsonPath("$.data[?(@.type == 'INSPECTION_CRITICAL_FAILURE')].emailEnabled").value(true));

        notify(user, user);
        delivery.deliverPending();

        assertThat(emailStatus(user)).isEqualTo("NONE");
        as(user, get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void oneUserCannotReachAnothersInboxInTheSameOrganisation() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        notify(owner, member);
        String id = com.jayway.jsonpath.JsonPath.read(as(member, get("/api/v1/notifications"))
                .andReturn().getResponse().getContentAsString(), "$.data.items[0].id");

        as(owner, get("/api/v1/notifications")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(owner, postJson("/api/v1/notifications/" + id + "/read", Map.of())).andExpect(status().isNotFound());
        as(member, get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void notificationsCanBeMarkedRead() throws Exception {
        Session user = register();
        notify(user, user);
        notify(user, user);
        String id = com.jayway.jsonpath.JsonPath.read(as(user, get("/api/v1/notifications"))
                .andReturn().getResponse().getContentAsString(), "$.data.items[0].id");

        as(user, postJson("/api/v1/notifications/" + id + "/read", Map.of())).andExpect(status().isNoContent());
        as(user, get("/api/v1/notifications?unreadOnly=true")).andExpect(jsonPath("$.data.totalItems").value(1));
        as(user, postJson("/api/v1/notifications/read-all", Map.of())).andExpect(status().isNoContent());
        as(user, get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void theApplicationRoleCannotReadAcrossTenantsThroughTheDeliveryFunction() throws Exception {
        Session user = register();
        notify(user, user);

        asAppRole(null, sql -> {
            assertThat(sql.queryForList("SELECT tenants_with_pending_email()", UUID.class)).contains(user.tenantId());
            assertThat(sql.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        });
    }
}
