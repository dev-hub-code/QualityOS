package com.qualityos;

import com.qualityos.common.mail.MailGateway;
import com.qualityos.common.mail.MailMessage;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Keeps emails in memory, and can be told to fail. Account emails arrive asynchronously, so readers wait. */
@Primary
@Component
public class RecordingMailGateway implements MailGateway {

    private static final Pattern TOKEN = Pattern.compile("token=(\\S+)");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void send(MailMessage message) {
        if (failing) {
            throw new MailDeliveryException(new IllegalStateException("Mail server down"));
        }
        sent.add(message);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public List<MailMessage> to(String address) {
        String normalized = address.toLowerCase(Locale.ROOT);
        return sent.stream().filter(message -> message.to().equals(normalized)).toList();
    }

    /** Waits for a later email than the one that carried {@code previous}. */
    public String awaitNewToken(String address, String subject, String previous) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            String token = awaitToken(address, subject);
            if (!token.equals(previous)) {
                return token;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No new email to " + address + " about " + subject);
    }

    /** The token in the newest email to this address with this subject fragment. */
    public String awaitToken(String address, String subject) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<MailMessage> matching =
                    to(address).stream().filter(message -> message.subject().contains(subject)).toList();
            if (!matching.isEmpty()) {
                Matcher matcher = TOKEN.matcher(matching.getLast().text());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No email to " + address + " about " + subject);
    }
}
