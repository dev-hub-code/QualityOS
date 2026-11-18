package com.qualityos.common.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real email through SendGrid's v3 Mail Send API, for UAT. Click and open tracking are off: SendGrid would otherwise
 * rewrite every link through its own address, including one-time sign-in and reset links, and add a tracking image.
 * A refusal or no answer throws, so the message is kept and sent again later, as with SMTP.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "qualityos.mail.provider", havingValue = "sendgrid")
class SendGridMailGateway implements MailGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient rest;
    private final JsonMapper json;
    private final Map<String, Object> from;
    private final boolean sandbox;

    SendGridMailGateway(
            JsonMapper json,
            @Value("${qualityos.mail.from}") String from,
            @Value("${qualityos.mail.sendgrid.api-key:}") String apiKey,
            @Value("${qualityos.mail.sendgrid.base-url:https://api.sendgrid.com}") String baseUrl,
            @Value("${qualityos.mail.sendgrid.sandbox:false}") boolean sandbox) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("MAIL_PROVIDER is sendgrid, so SENDGRID_API_KEY must be set.");
        }
        this.json = json;
        this.from = sender(from);
        this.sandbox = sandbox;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey.trim()))
                .build();
    }

    /** "ProductionOS <no-reply@example.com>" becomes SendGrid's name and email. The address must be a verified sender. */
    static Map<String, Object> sender(String from) {
        try {
            InternetAddress address = new InternetAddress(from, true);
            Map<String, Object> sender = new LinkedHashMap<>();
            sender.put("email", address.getAddress());
            if (StringUtils.hasText(address.getPersonal())) {
                sender.put("name", address.getPersonal());
            }
            return sender;
        } catch (AddressException e) {
            throw new IllegalStateException("MAIL_FROM is not a valid address: " + from, e);
        }
    }

    @Override
    public void send(MailMessage message) {
        try {
            rest.post().uri("/v3/mail/send").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body(message))).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Never the body: SendGrid can quote the message back.
            log.warn("SendGrid refused \"{}\" with status {}", message.subject(), e.getStatusCode().value());
            throw new MailDeliveryException(e);
        } catch (RestClientException e) {
            log.warn("SendGrid did not answer for \"{}\": {}", message.subject(), e.getClass().getSimpleName());
            throw new MailDeliveryException(e);
        }
    }

    Map<String, Object> body(MailMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("personalizations", List.of(Map.of("to", List.of(Map.of("email", message.to())))));
        body.put("from", from);
        body.put("subject", message.subject());
        // Plain text first, as mail clients show the last part they can.
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text/plain", "value", message.text()));
        if (StringUtils.hasText(message.html())) {
            content.add(Map.of("type", "text/html", "value", message.html()));
        }
        body.put("content", content);
        if (message.logo() != null) {
            body.put("attachments", List.of(Map.of(
                    "content", Base64.getEncoder().encodeToString(message.logo().data()),
                    "type", message.logo().contentType(),
                    "filename", "logo" + extension(message.logo().contentType()),
                    "disposition", "inline",
                    "content_id", "logo")));
        }
        body.put("tracking_settings", Map.of(
                "click_tracking", Map.of("enable", false, "enable_text", false),
                "open_tracking", Map.of("enable", false)));
        if (sandbox) {
            body.put("mail_settings", Map.of("sandbox_mode", Map.of("enable", true)));
        }
        return body;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> "";
        };
    }
}
