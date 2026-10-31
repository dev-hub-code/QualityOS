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
import org.junit.jupiter.params.provider.ValueSource;

class EmailRendererTest {

    private static final List<String> LANGUAGES = List.of("en", "hi", "mr");
    private static final Pattern DEVANAGARI = Pattern.compile("\\p{IsDevanagari}");
    private final EmailRenderer renderer = new EmailRenderer();

    private static Map<String, Object> variables() {
        Map<String, Object> values = new HashMap<>();
        for (String name : List.of("recipientName", "organizationName", "brandName", "inspectionNumber", "batchNumber",
                "ncrNumber", "ncrTitle", "capaNumber", "capaTitle", "dueDate", "expiryMinutes", "lockMinutes",
                "instrumentName", "auditNumber", "amount", "title", "body")) {
            values.put(name, "value-" + name);
        }
        values.put("actionUrl", "https://app.example/path");
        return values;
    }

    @ParameterizedTest
    @EnumSource(EmailTemplate.class)
    void everyTemplateRendersInEveryLanguageWithHtmlAndText(EmailTemplate template) {
        for (String language : LANGUAGES) {
            RenderedEmail email = renderer.render(template, language, variables());
            assertThat(email.subject()).isNotBlank().doesNotContain("{{");
            assertThat(email.html()).contains("<html lang=\"" + language + "\"").contains("<a href=")
                    .contains("max-width:600px").doesNotContain("{{");
            assertThat(email.text()).contains("https://app.example/path").doesNotContain("<p").doesNotContain("{{");
            if (!language.equals("en")) {
                assertThat(DEVANAGARI.matcher(email.text()).find()).as(template + " " + language).isTrue();
            }
        }
    }

    @Test
    void hindiAndMarathiAreNotTheSameText() {
        assertThat(renderer.render(EmailTemplate.WELCOME, "hi", variables()).text())
                .isNotEqualTo(renderer.render(EmailTemplate.WELCOME, "mr", variables()).text());
    }

    @Test
    void valuesAreEscapedInHtmlButReadableInText() {
        Map<String, Object> values = variables();
        values.put("recipientName", "<script>alert('x')</script>");
        RenderedEmail email = renderer.render(EmailTemplate.WELCOME, "en", values);
        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(email.text()).contains("<script>alert('x')</script>");
    }

    @Test
    void aValueCannotAddAMailHeader() {
        Map<String, Object> values = variables();
        values.put("title", "Hello\r\nBcc: someone@example.com");
        assertThat(renderer.render(EmailTemplate.GENERAL, "en", values).subject()).doesNotContain("\n", "\r");
    }

    @Test
    void aLanguageWithoutTemplatesFallsBackToEnglish() {
        assertThat(renderer.render(EmailTemplate.WELCOME, "ta", variables()).subject()).isEqualTo("Welcome to QualityOS");
    }

    @Test
    void aMissingVariableFailsInsteadOfSendingABlank() {
        Map<String, Object> values = variables();
        values.remove("recipientName");
        assertThatThrownBy(() -> renderer.render(EmailTemplate.WELCOME, "en", values))
                .isInstanceOf(EmailTemplateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "hi", "mr"})
    void noTemplateAsksForASecret(String language) throws IOException {
        Pattern secret = Pattern.compile("\\{\\{\\{?\\s*\\w*(password|secret|token)\\w*", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/email-templates", language))) {
            assertThat(files.filter(Files::isRegularFile)).allSatisfy(file ->
                    assertThat(secret.matcher(Files.readString(file)).find()).as(file.toString()).isFalse());
        }
    }
}
