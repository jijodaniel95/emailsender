package com.email.emailsender.service;

import com.email.emailsender.dto.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
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

    public void processMessageAsync(AcknowledgeablePubsubMessage message) {
        PubsubMessage pubsubMessage = message.getPubsubMessage();
        String messageId = pubsubMessage.getMessageId();
        String payload = pubsubMessage.getData().toStringUtf8();
        
        log.info("Processing message ID: {} with Mailgun", messageId);

        try {
            // Parse the message
            NotificationMessage msg;
            try {
                msg = objectMapper.readValue(payload, NotificationMessage.class);
            } catch (Exception e) {
                log.error("Failed to parse message payload: {}", payload, e);
                message.nack();
                log.info("Message nacked due to parsing error: {}", messageId);
                return;
            }
            
            log.info("Successfully parsed message from: {}", msg.getSender());

            // Validate message fields
            if (msg.getSender() == null || msg.getSender().isEmpty()) {
                log.error("Message missing sender email: {}", payload);
                message.nack();
                log.info("Message nacked due to missing sender: {}", messageId);
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
                
                // Explicitly acknowledge the message
                try {
                    message.ack();
                    log.info("Successfully acknowledged message: {}", messageId);
                } catch (Exception ackEx) {
                    log.error("Failed to acknowledge message: {}. Error: {}", 
                            messageId, ackEx.getMessage(), ackEx);
                }
            } catch (Exception e) {
                log.error("Failed to send email via Mailgun: {}", e.getMessage(), e);
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
} 