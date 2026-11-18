package com.qualityos.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks real HTTP to a local server, so the request is checked as it would leave the application for SendGrid. */
class SendGridMailGatewayTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> sent = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(202);
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (status.get() == 202 ? "" : "{\"errors\":[{\"message\":\"The from address does not match\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SendGridMailGateway gateway(boolean sandbox) {
        return new SendGridMailGateway(json, "ProductionOS <no-reply@example.com>", "SG.test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(), sandbox);
    }

    @Test
    void sendsBothBodiesFromTheVerifiedSenderWithTrackingOff() {
        gateway(false).send(new MailMessage("asha@example.com", "[UAT] Reset your password", "Reset: https://x/r?t=1",
                "<p><a href=\"https://x/r?t=1\">Reset</a></p>"));

        assertThat(path.get()).isEqualTo("/v3/mail/send");
        assertThat(authorization.get()).isEqualTo("Bearer SG.test-key");
        JsonNode body = json.readTree(sent.get());
        assertThat(body.at("/personalizations/0/to/0/email").asString()).isEqualTo("asha@example.com");
        assertThat(body.at("/from/email").asString()).isEqualTo("no-reply@example.com");
        assertThat(body.at("/from/name").asString()).isEqualTo("ProductionOS");
        assertThat(body.get("subject").asString()).isEqualTo("[UAT] Reset your password");
        assertThat(body.at("/content/0/type").asString()).isEqualTo("text/plain");
        assertThat(body.at("/content/1/type").asString()).isEqualTo("text/html");
        // One-time links must reach the person as written, not through SendGrid's redirect.
        assertThat(body.at("/tracking_settings/click_tracking/enable").asBoolean()).isFalse();
        assertThat(body.at("/tracking_settings/click_tracking/enable_text").asBoolean()).isFalse();
        assertThat(body.at("/tracking_settings/open_tracking/enable").asBoolean()).isFalse();
        assertThat(body.has("mail_settings")).isFalse();
        assertThat(body.has("attachments")).isFalse();
    }

    @Test
    void theLogoTravelsInlineUnderTheIdTheHtmlUses() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        gateway(false).send(new MailMessage("asha@example.com", "Hello", "Hi", "<img src=\"cid:logo\">",
                new MailMessage.InlineImage("image/png", png)));

        JsonNode logo = json.readTree(sent.get()).at("/attachments/0");
        assertThat(logo.get("disposition").asString()).isEqualTo("inline");
        assertThat(logo.get("content_id").asString()).isEqualTo("logo");
        assertThat(logo.get("type").asString()).isEqualTo("image/png");
        assertThat(logo.get("filename").asString()).isEqualTo("logo.png");
        assertThat(Base64.getDecoder().decode(logo.get("content").asString())).isEqualTo(png);
    }

    @Test
    void sandboxModeChecksTheRequestWithoutDelivering() {
        gateway(true).send(new MailMessage("asha@example.com", "Hello", "Hi", null));

        JsonNode body = json.readTree(sent.get());
        assertThat(body.at("/mail_settings/sandbox_mode/enable").asBoolean()).isTrue();
        assertThat(body.get("content")).hasSize(1);
    }

    @Test
    void aRefusalOrNoAnswerFailsTheSendSoItIsTriedAgain() {
        status.set(403);
        assertThatThrownBy(() -> gateway(false).send(new MailMessage("asha@example.com", "Hello", "Hi", "<p>Hi</p>")))
                .isInstanceOf(MailGateway.MailDeliveryException.class);

        SendGridMailGateway unreachable = gateway(false);
        server.stop(0);
        assertThatThrownBy(() -> unreachable.send(new MailMessage("asha@example.com", "Hello", "Hi", "<p>Hi</p>")))
                .isInstanceOf(MailGateway.MailDeliveryException.class);
    }

    @Test
    void itWillNotStartWithoutAKeyOrWithAnInvalidSender() {
        assertThatThrownBy(() -> new SendGridMailGateway(json, "no-reply@example.com", " ", "http://x", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SENDGRID_API_KEY");
        assertThatThrownBy(() -> SendGridMailGateway.sender("not an address <"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
        assertThat(SendGridMailGateway.sender("no-reply@example.com")).containsOnlyKeys("email");
    }
}
