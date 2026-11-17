package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.subscription.dto.CustomerSupportDtos.CustomerTicket;
import com.qualityos.subscription.dto.CustomerSupportDtos.SupportMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support requests raised by an organisation's own members. A member follows the requests they raised; someone who
 * manages the organisation follows all of its requests. Support's internal notes never reach them.
 */
@Service
public class CustomerSupport {

    private static final String ENTITY = "SupportTicket";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    CustomerSupport(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID raise(String subject, String message) {
        UUID ticket = jdbc.queryForObject(
                "SELECT customer_support_open(?, ?)", UUID.class, subject.trim(), message.trim());
        audit.record("SUPPORT_TICKET_RAISED", ENTITY, ticket, null, Map.of("subject", subject.trim()));
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<CustomerTicket> tickets(boolean all) {
        return jdbc.query("SELECT * FROM customer_support_tickets(?)", (rows, row) -> new CustomerTicket(
                rows.getObject("id", UUID.class), rows.getString("subject"), rows.getString("status"),
                rows.getString("opened_by"), rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant()), all);
    }

    @Transactional(readOnly = true)
    public CustomerTicket ticket(UUID id, boolean all) {
        return tickets(all).stream().filter(ticket -> ticket.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id.toString()));
    }

    @Transactional(readOnly = true)
    public List<SupportMessage> messages(UUID id, boolean all) {
        ticket(id, all);
        return jdbc.query("SELECT * FROM customer_support_messages(?, ?)", (rows, row) -> new SupportMessage(
                rows.getObject("id", UUID.class), rows.getString("author"), rows.getBoolean("from_support"),
                rows.getString("body"), rows.getTimestamp("created_at").toInstant()), id, all);
    }

    @Transactional
    public void reply(UUID id, String body, boolean all) {
        String outcome = jdbc.queryForObject(
                "SELECT customer_support_reply(?, ?, ?)", String.class, id, body.trim(), all);
        switch (outcome) {
            case "NOT_FOUND" -> throw new ResourceNotFoundException(ENTITY, id.toString());
            case "CLOSED" -> throw new BusinessRuleException("This request is closed. Raise a new one instead.");
            default -> audit.record("SUPPORT_TICKET_REPLIED", ENTITY, id);
        }
    }
}
