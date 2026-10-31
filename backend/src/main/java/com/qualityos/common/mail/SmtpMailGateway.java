package com.qualityos.common.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailGateway(JavaMailSender sender, @Value("${qualityos.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper mail = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            mail.setFrom(from);
            mail.setTo(message.to());
            mail.setSubject(message.subject());
            mail.setText(message.text(), message.html());
            sender.send(mime);
        } catch (MailException | MessagingException ex) {
            throw new MailDeliveryException(ex);
        }
    }
}
