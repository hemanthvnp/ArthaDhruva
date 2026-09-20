package com.arthadhruva.riskengine.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Transactional email over SMTP. Degrades gracefully, matching the activation-link behavior: with
 * no SMTP host configured the message is logged instead of sent, and a delivery failure is logged
 * and swallowed, so the request that triggered the email always succeeds. Callers must therefore
 * never depend on the email having been sent (a reset request looks identical either way).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender sender;
    private final String from;

    public EmailService(ObjectProvider<JavaMailSender> sender,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${email.from:no-reply@arthadhruva.local}") String from) {
        // An empty host still makes Spring create a JavaMailSender, so treat blank as "not configured".
        this.sender = smtpHost == null || smtpHost.isBlank() ? null : sender.getIfAvailable();
        this.from = from;
    }

    /** @return true if handed to the SMTP server; false if unconfigured or it failed */
    public boolean send(String to, String subject, String body) {
        if (sender == null) {
            log.info("SMTP not configured; would have sent email to {} :: {} :: {}", to, subject, body);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Email to {} failed: {}", to, e.toString());
            return false;
        }
    }
}
