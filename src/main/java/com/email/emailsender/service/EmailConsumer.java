package com.email.emailsender.service;


import com.email.emailsender.dto.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailConsumer(EmailService emailService) {
        this.emailService = emailService;
    }

    public void processMessage(String payload) {
        log.info("Received message payload: {}", payload);

        try {
            NotificationMessage msg = objectMapper.readValue(payload, NotificationMessage.class);
            log.info("Successfully parsed message for sender: {}", msg.getSender());

            emailService.sendHtmlEmail(
                    msg.getSender(),
                    msg.getSubject(),
                    msg.getMessage(),
                    msg.getFullName()
            );
            log.info("Email sent successfully to: {}", msg.getSender());

        } catch (Exception e) {
            log.error("Error processing email message: {}", payload, e);
            throw new RuntimeException("Email processing failed", e); // triggers retry
        }
    }
}
