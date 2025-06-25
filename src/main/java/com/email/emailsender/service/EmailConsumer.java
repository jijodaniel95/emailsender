package com.email.emailsender.service;

import com.email.emailsender.dto.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;
import com.google.pubsub.v1.PubsubMessage;
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

    public void processMessageAsync(AcknowledgeablePubsubMessage message) {
        PubsubMessage pubsubMessage = message.getPubsubMessage();
        String messageId = pubsubMessage.getMessageId();
        String payload = pubsubMessage.getData().toStringUtf8();
        
        log.info("Processing message ID: {} with standard email service", messageId);

        try {
            // Parse the message
            NotificationMessage msg;
            try {
                msg = objectMapper.readValue(payload, NotificationMessage.class);
            } catch (Exception e) {
                log.error("Failed to parse message payload: {}", payload, e);
                try {
                    message.nack();
                    log.info("Message nacked due to parsing error: {}", messageId);
                } catch (Exception nackEx) {
                    log.error("Failed to nack message: {}. Error: {}", 
                            messageId, nackEx.getMessage(), nackEx);
                }
                return;
            }
            
            log.info("Successfully parsed message from: {}", msg.getSender());

            // Validate message fields
            if (msg.getSender() == null || msg.getSender().isEmpty()) {
                log.error("Message missing sender email: {}", payload);
                try {
                    message.nack();
                    log.info("Message nacked due to missing sender: {}", messageId);
                } catch (Exception nackEx) {
                    log.error("Failed to nack message: {}. Error: {}", 
                            messageId, nackEx.getMessage(), nackEx);
                }
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
                
                // Explicitly acknowledge the message
                try {
                    message.ack();
                    log.info("Successfully acknowledged message: {}", messageId);
                } catch (Exception ackEx) {
                    log.error("Failed to acknowledge message: {}. Error: {}", 
                            messageId, ackEx.getMessage(), ackEx);
                }
            } catch (Exception e) {
                log.error("Failed to send email: {}", e.getMessage(), e);
                try {
                    message.nack();
                    log.info("Message nacked due to email sending failure: {}", messageId);
                } catch (Exception nackEx) {
                    log.error("Failed to nack message: {}. Error: {}", 
                            messageId, nackEx.getMessage(), nackEx);
                }
            }

        } catch (RuntimeException e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            try {
                message.nack();
                log.info("Message nacked due to processing error: {}", messageId);
            } catch (Exception nackEx) {
                log.error("Failed to nack message: {}. Error: {}", 
                        messageId, nackEx.getMessage(), nackEx);
            }
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
                log.error("Failed to send email: {}", e.getMessage(), e);
                throw new RuntimeException("Email sending failed", e);
            }

        } catch (RuntimeException e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            throw e; // Rethrow to trigger nack
        }
    }
}
