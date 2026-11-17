package com.qualityos.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EmailRendererTest {

    private final EmailRenderer renderer = new EmailRenderer();

    private static Map<String, Object> variables() {
        Map<String, Object> values = new HashMap<>();
        for (String name : List.of("recipientName", "organizationName", "brandName", "inspectionNumber", "batchNumber",
                "ncrNumber", "ncrTitle", "capaNumber", "capaTitle", "dueDate", "expiryMinutes", "lockMinutes",
                "instrumentName", "auditNumber", "amount", "title", "body", "recordNumber", "summary", "note",
                "where", "reason", "receiptNumber", "lots", "supplierName", "actionDescription", "disposition",
                "correctionNumber", "outcome", "count", "impact", "auditTitle", "planName", "invoiceNumber", "headline",
                "usage", "advice", "message", "subject", "update")) {
            values.put(name, "value-" + name);
        }
        values.put("actionUrl", "https://app.example/path");
        return values;
    }

    @ParameterizedTest
    @EnumSource(EmailTemplate.class)
    void everyTemplateRendersInEnglishWithHtmlAndText(EmailTemplate template) {
        RenderedEmail email = renderer.render(template, variables());
        assertThat(email.subject()).isNotBlank().doesNotContain("{{");
        assertThat(email.html()).contains("<html lang=\"en\"").contains("<a href=")
                .contains("max-width:600px").doesNotContain("{{");
        assertThat(email.text()).contains("https://app.example/path").doesNotContain("<p").doesNotContain("{{");
    }

    @Test
    void valuesAreEscapedInHtmlButReadableInText() {
        Map<String, Object> values = variables();
        values.put("recipientName", "<script>alert('x')</script>");
        RenderedEmail email = renderer.render(EmailTemplate.WELCOME, values);
        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(email.text()).contains("<script>alert('x')</script>");
    }

    @Test
    void aValueCannotAddAMailHeader() {
        Map<String, Object> values = variables();
        values.put("title", "Hello\r\nBcc: someone@example.com");
        assertThat(renderer.render(EmailTemplate.GENERAL, values).subject()).doesNotContain("\n", "\r");
    }

    @Test
    void theSubjectIsEnglish() {
        assertThat(renderer.render(EmailTemplate.WELCOME, variables()).subject()).isEqualTo("Welcome to ProductionOS");
    }

    @Test
    void aMissingVariableFailsInsteadOfSendingABlank() {
        Map<String, Object> values = variables();
        values.remove("recipientName");
        assertThatThrownBy(() -> renderer.render(EmailTemplate.WELCOME, values))
                .isInstanceOf(EmailTemplateException.class);
    }

    @Test
    void noTemplateAsksForASecret() throws IOException {
        Pattern secret = Pattern.compile("\\{\\{\\{?\\s*\\w*(password|secret|token)\\w*", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/email-templates"))) {
            assertThat(files.filter(Files::isRegularFile)).allSatisfy(file ->
                    assertThat(secret.matcher(Files.readString(file)).find()).as(file.toString()).isFalse());
        }
    }
}
