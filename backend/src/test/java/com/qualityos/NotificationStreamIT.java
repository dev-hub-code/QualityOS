package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.tenant.TenantContext;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationStreamIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notifications;

    private MockHttpServletResponse open(Session session) throws Exception {
        return as(session, get("/api/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();
    }

    private void notify(Session actor, Session recipient) {
        TenantContext.runAs(actor.tenantId(), actor.userId(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> notifications.notify(Set.of(recipient.userId()),
                        NotificationType.INSPECTION_SENT_BACK, "Title", "Body", "/inspections/1")));
    }

    private static long changes(MockHttpServletResponse stream) throws Exception {
        return stream.getContentAsString().lines().filter("data:changed"::equals).count();
    }

    private static void awaitChanges(MockHttpServletResponse stream, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (changes(stream) < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(changes(stream)).isEqualTo(expected);
    }

    @Test
    void aNewNotificationIsAnnouncedAtOnceToItsRecipientOnly() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        Session elsewhere = register();

        MockHttpServletResponse memberStream = open(member);
        MockHttpServletResponse ownerStream = open(owner);
        MockHttpServletResponse otherStream = open(elsewhere);
        assertThat(memberStream.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(memberStream.getContentAsString()).contains("event:notifications", "data:connected");

        notify(owner, member);
        awaitChanges(memberStream, 1);
        // The event names nothing: the page reads the inbox, through its usual checks.
        assertThat(memberStream.getContentAsString()).doesNotContain("Title", "inspections");
        assertThat(changes(ownerStream)).isZero();
        assertThat(changes(otherStream)).isZero();

        // Reading in one tab updates the bell in the others.
        as(member, post("/api/v1/notifications/read-all")).andExpect(status().isNoContent());
        awaitChanges(memberStream, 2);
    }

    @Test
    void nothingIsAnnouncedForAChangeThatRolledBack() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        MockHttpServletResponse stream = open(member);

        TenantContext.runAs(owner.tenantId(), owner.userId(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    notifications.notify(Set.of(member.userId()), NotificationType.INSPECTION_SENT_BACK,
                            "Title", "Body", "/inspections/1");
                    status.setRollbackOnly();
                }));
        Thread.sleep(200);
        assertThat(changes(stream)).isZero();
    }

    @Test
    void theStreamNeedsASignedInMember() throws Exception {
        mvc.perform(get("/api/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }
}
