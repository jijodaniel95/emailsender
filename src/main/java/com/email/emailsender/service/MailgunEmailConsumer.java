package com.email.emailsender.service;

import com.email.emailsender.dto.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Profile("mailgun")
public class MailgunEmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(MailgunEmailConsumer.class);

    private final MailgunEmailService mailgunEmailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MailgunEmailConsumer(MailgunEmailService mailgunEmailService) {
        this.mailgunEmailService = mailgunEmailService;
        log.info("Initialized Mailgun Email Consumer");
    }

    //@Async
    public void processMessageAsync(AcknowledgeablePubsubMessage message) {
        PubsubMessage pubsubMessage = message.getPubsubMessage();
        String messageId = pubsubMessage.getMessageId();
        String payload = pubsubMessage.getData().toStringUtf8();
        
        log.info("Asynchronously processing message ID: {} with Mailgun", messageId);

        try {
            // Parse the message
            NotificationMessage msg;
            try {
                msg = objectMapper.readValue(payload, NotificationMessage.class);
            } catch (Exception e) {
                log.error("Failed to parse message payload: {}", payload, e);
                message.nack();
                return;
            }
            
            log.info("Successfully parsed message from: {}", msg.getSender());

            // Validate message fields
            if (msg.getSender() == null || msg.getSender().isEmpty()) {
                log.error("Message missing sender email: {}", payload);
                message.nack();
                return;
            }

            // Send the email via Mailgun
            try {
                mailgunEmailService.sendHtmlEmail(
                        msg.getSender(),
                        msg.getSubject(),
                        msg.getMessage(),
                        msg.getFullName()
                );
                log.info("Email sent successfully via Mailgun to: {}", msg.getSender());
                message.ack();
                log.info("Successfully processed and acknowledged message: {}", messageId);
            } catch (Exception e) {
                log.error("Failed to send email via Mailgun: {}", e.getMessage(), e);
                message.nack();
                log.error("Nacked message due to email sending failure: {}", messageId);
            }

        } catch (RuntimeException e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            message.nack();
            log.error("Nacked message due to processing error: {}", messageId);
        }
    }
} 