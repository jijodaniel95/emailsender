package com.email.emailsender.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.SocketException;

@Service
public class EmailService {
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.sender}")
    private String senderFromConfig;

    @Value("${app.mail.owner}")
    private String siteOwner;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Retryable(value = {SocketException.class}, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void sendHtmlEmail(String email, String subject, String body, String fullName) throws Exception {
        try {
            logger.debug("Attempting to send email to: {}", email);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("email", email);
            context.setVariable("subject", subject);
            context.setVariable("message", body);

            String html = templateEngine.process("email-template", context);

            helper.setFrom(senderFromConfig);
            helper.setTo(siteOwner);
            helper.setSubject("New Contact: " + subject);
            helper.setText(html, true);

            mailSender.send(message);
            logger.info("Successfully sent email to: {}", email);
        } catch (Exception e) {
            logger.error("Failed to send email to: {}. Attempt will be retried. Error: {}", email, e.getMessage());
            throw e;
        }
    }
}

