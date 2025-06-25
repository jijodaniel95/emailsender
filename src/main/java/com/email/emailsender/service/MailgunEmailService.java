package com.email.emailsender.service;

import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.client.MailgunClient;
import com.mailgun.model.message.Message;
import com.mailgun.model.message.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Profile("mailgun")
public class MailgunEmailService {
    private static final Logger logger = LoggerFactory.getLogger(MailgunEmailService.class);

    private final TemplateEngine templateEngine;
    private final MailgunMessagesApi mailgunMessagesApi;
    private final String domain;

    @Value("${app.mail.sender}")
    private String senderFromConfig;

    @Value("${app.mail.owner}")
    private String siteOwner;

    public MailgunEmailService(TemplateEngine templateEngine, 
                              @Value("${mailgun.api.key}") String apiKey,
                              @Value("${mailgun.domain}") String domain) {
        this.templateEngine = templateEngine;
        this.domain = domain;
        this.mailgunMessagesApi = MailgunClient.config(apiKey).createApi(MailgunMessagesApi.class);
        
        logger.info("Initialized Mailgun Email Service with domain: {}", domain);
    }

    public void sendHtmlEmail(String email, String subject, String body, String fullName) throws Exception {
        try {
            logger.info("Attempting to send email to: {} via Mailgun", email);
            
            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("email", email);
            context.setVariable("subject", subject);
            context.setVariable("message", body);

            String html = templateEngine.process("email-template", context);

            Message message = Message.builder()
                    .from(senderFromConfig)
                    .to(siteOwner)
                    .subject("New Contact: " + subject)
                    .html(html)
                    .build();

            MessageResponse response = mailgunMessagesApi.sendMessage(domain, message);
            
            logger.info("Successfully sent email to: {} via Mailgun. Response ID: {}", 
                    email, response.getId());
        } catch (Exception e) {
            logger.error("Failed to send email via Mailgun to: {}. Error: {}", 
                    email, e.getMessage(), e);
            throw e;
        }
    }
} 