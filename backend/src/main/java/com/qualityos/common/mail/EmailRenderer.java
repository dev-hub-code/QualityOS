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
 * Renders a template inside the layout. Email is in English only. A variable the template needs and the caller left
 * out is an error, rather than a blank in somebody's email.
 */
@Component
public class EmailRenderer {

    private static final String ROOT = "email-templates/";
    private static final String SUBJECT_PREFIX = "Subject: ";

    private final Mustache.Compiler html = Mustache.compiler();
    private final Mustache.Compiler text = Mustache.compiler().escapeHTML(false);
    private final Map<String, Template> compiled = new ConcurrentHashMap<>();

    public RenderedEmail render(EmailTemplate template, Map<String, ?> variables) {
        try {
            String source = read(template.path() + ".txt");
            int bodyStart = source.indexOf('\n');
            String subject = text.compile(source.substring(SUBJECT_PREFIX.length(), bodyStart)).execute(variables)
                    .replaceAll("[\\r\\n]+", " ")
                    .strip();
            Map<String, Object> page = new HashMap<>(variables);
            page.put("subject", subject);
            page.put("content", loadHtml(template.path() + ".html").execute(variables));
            String htmlBody = loadHtml("layout.html").execute(page);
            page.put("content", text.compile(source.substring(bodyStart).strip()).execute(variables));
            String textBody = text.compile(read("layout.txt")).execute(page);
            return new RenderedEmail(subject, htmlBody, textBody);
        } catch (MustacheException | IOException ex) {
            throw new EmailTemplateException("Email template " + template + " could not be rendered", ex);
        }
    }

    private Template loadHtml(String file) throws IOException {
        Template found = compiled.get(file);
        if (found == null) {
            found = html.compile(read(file));
            compiled.put(file, found);
        }
        return found;
    }

    private static String read(String file) throws IOException {
        return new ClassPathResource(ROOT + file).getContentAsString(StandardCharsets.UTF_8);
    }
}
