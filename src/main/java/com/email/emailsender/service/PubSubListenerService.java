package com.email.emailsender.service;

import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;

import java.util.List;

@Service
public class PubSubListenerService {
    private static final Logger logger = LoggerFactory.getLogger(PubSubListenerService.class);
    private static final int MAX_MESSAGES_PER_PULL = 10;

    private final EmailConsumer emailConsumer;
    private final PubSubTemplate pubSubTemplate;

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;

    public PubSubListenerService(EmailConsumer emailConsumer, PubSubTemplate pubSubTemplate) {
        this.emailConsumer = emailConsumer;
        this.pubSubTemplate = pubSubTemplate;
    }

    /**
     * Pull messages every 15 seconds
     */
    @Scheduled(fixedRate = 15000)
    public void pullMessages() {
        logger.info("Pulling messages from subscription: {}", subscriptionName);

        // Synchronously pull messages, with a maximum of 10 messages per pull
        List<AcknowledgeablePubsubMessage> messages = pubSubTemplate.pull(subscriptionName, MAX_MESSAGES_PER_PULL, true);

        if (messages.isEmpty()) {
            logger.debug("No messages found in the subscription.");
            return;
        }

        logger.info("Received {} message(s).", messages.size());

        try {
            for (AcknowledgeablePubsubMessage message : messages) {
                PubsubMessage pubsubMessage = message.getPubsubMessage();
                String messageId = pubsubMessage.getMessageId();
                String payload = pubsubMessage.getData().toStringUtf8();
                
                logger.info("Processing message ID: {}", messageId);
                
                try {
                    // Process message
                    emailConsumer.processMessage(payload);
                    
                    // Acknowledge successful processing
                    message.ack();
                    logger.info("Successfully processed and acknowledged message: {}", messageId);
                } catch (Exception e) {
                    // Nack the message on processing failure so it can be retried
                    message.nack();
                    logger.error("Error processing message: {}. Error: {}", messageId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            // If we hit an error processing the batch, nack all messages
            logger.error("Error processing message batch, nacking all messages", e);
            messages.forEach(AcknowledgeablePubsubMessage::nack);
        }
    }

    /**
     * Check if the service is healthy
     */
    public boolean isHealthy() {
        try {
            // Try to pull 0 messages to check connectivity
            pubSubTemplate.pull(subscriptionName, 0, true);
            return true;
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return false;
        }
    }
} 