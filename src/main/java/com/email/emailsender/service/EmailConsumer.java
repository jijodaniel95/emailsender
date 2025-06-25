package com.email.emailsender.service;

import com.email.emailsender.dto.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailConsumer(EmailService emailService) {
        this.emailService = emailService;
    }

    //@Async
    public void processMessageAsync(AcknowledgeablePubsubMessage message) {
        PubsubMessage pubsubMessage = message.getPubsubMessage();
        String messageId = pubsubMessage.getMessageId();
        String payload = pubsubMessage.getData().toStringUtf8();
        
        log.info("Asynchronously processing message ID: {} in thread: {}", 
                messageId, Thread.currentThread().getName());

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

            // Send the email
            try {
                emailService.sendHtmlEmail(
                        msg.getSender(),
                        msg.getSubject(),
                        msg.getMessage(),
                        msg.getFullName()
                );
                log.info("Email sent successfully to: {}", msg.getSender());
                message.ack();
                log.info("Successfully processed and acknowledged message: {}", messageId);
            } catch (Exception e) {
                log.error("Failed to send email for message: {}", payload, e);
                message.nack();
                log.error("Nacked message due to email sending failure: {}", messageId);
            }

        } catch (RuntimeException e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            message.nack();
            log.error("Nacked message due to processing error: {}", messageId);
        }
    }
    
    // Keep the original method for backward compatibility if needed
    public void processMessage(String payload) {
        log.info("Processing message payload: {}", payload);

        try {
            // Parse the message
            NotificationMessage msg;
            try {
                msg = objectMapper.readValue(payload, NotificationMessage.class);
            } catch (Exception e) {
                log.error("Failed to parse message payload: {}", payload, e);
                throw new RuntimeException("Invalid message format", e);
            }
            
            log.info("Successfully parsed message from: {}", msg.getSender());

            // Validate message fields
            if (msg.getSender() == null || msg.getSender().isEmpty()) {
                log.error("Message missing sender email: {}", payload);
                throw new RuntimeException("Message missing sender email");
            }

            // Send the email
            try {
                emailService.sendHtmlEmail(
                        msg.getSender(),
                        msg.getSubject(),
                        msg.getMessage(),
                        msg.getFullName()
                );
                log.info("Email sent successfully to: {}", msg.getSender());
            } catch (Exception e) {
                log.error("Failed to send email for message: {}", payload, e);
                throw new RuntimeException("Email sending failed", e);
            }

        } catch (RuntimeException e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            throw e; // Rethrow to trigger nack
        }
    }
}
