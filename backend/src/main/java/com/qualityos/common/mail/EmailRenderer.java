package com.qualityos.common.mail;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders a template inside its language's layout. A language without a template falls back to English, so a new
 * language can be added one file at a time. A variable the template needs and the caller left out is an error, rather
 * than a blank in somebody's email.
 */
@Component
public class EmailRenderer {

    private static final String ROOT = "email-templates/";
    private static final String FALLBACK = "en";
    private static final String SUBJECT_PREFIX = "Subject: ";

    private final Mustache.Compiler html = Mustache.compiler();
    private final Mustache.Compiler text = Mustache.compiler().escapeHTML(false);
    private final Map<String, Template> compiled = new ConcurrentHashMap<>();

    public RenderedEmail render(EmailTemplate template, String language, Map<String, ?> variables) {
        try {
            String source = read(language, template.path() + ".txt");
            int bodyStart = source.indexOf('\n');
            String subject = text.compile(source.substring(SUBJECT_PREFIX.length(), bodyStart)).execute(variables)
                    .replaceAll("[\\r\\n]+", " ")
                    .strip();
            Map<String, Object> page = new HashMap<>(variables);
            page.put("subject", subject);
            page.put("content", loadHtml(language, template.path() + ".html").execute(variables));
            String htmlBody = loadHtml(language, "layout.html").execute(page);
            page.put("content", text.compile(source.substring(bodyStart).strip()).execute(variables));
            String textBody = text.compile(read(language, "layout.txt")).execute(page);
            return new RenderedEmail(subject, htmlBody, textBody);
        } catch (MustacheException | IOException ex) {
            throw new EmailTemplateException("Email template " + template + " could not be rendered", ex);
        }
    }

    private Template loadHtml(String language, String file) throws IOException {
        Template found = compiled.get(language + "/" + file);
        if (found == null) {
            found = html.compile(read(language, file));
            compiled.put(language + "/" + file, found);
        }
        return found;
    }

    private static String read(String language, String file) throws IOException {
        ClassPathResource localized = new ClassPathResource(ROOT + language + "/" + file);
        ClassPathResource resource = localized.exists() ? localized : new ClassPathResource(ROOT + FALLBACK + "/" + file);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
