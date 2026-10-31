package com.qualityos.common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());
        try {
            sender.send(mail);
        } catch (MailException ex) {
            throw new MailDeliveryException(ex);
        }
    }
}
