package com.email.emailsender.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.SocketException;
import java.net.SocketTimeoutException;

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

    @Retryable(
        value = {SocketException.class, SocketTimeoutException.class, MessagingException.class, MailException.class},
        maxAttempts = 5,
        backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void sendHtmlEmail(String email, String subject, String body, String fullName) throws Exception {
        try {
            logger.info("Attempting to send email to: {}", email);
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
            logger.error("Failed to send email to: {}. Error: {}", email, e.getMessage(), e);
            throw e;
        }
    }
    
    @Recover
    public void recoverFromEmailFailure(Exception e, String email, String subject, String body, String fullName) {
        logger.error("All retries failed for sending email to: {}. Final error: {}", email, e.getMessage());
        // Here you could implement a fallback mechanism:
        // 1. Store failed emails in a database for later retry
        // 2. Send a notification to admin
        // 3. Log to a monitoring system
    }
}

