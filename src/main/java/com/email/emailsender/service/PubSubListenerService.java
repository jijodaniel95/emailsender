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
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PubSubListenerService {
    private static final Logger logger = LoggerFactory.getLogger(PubSubListenerService.class);
    private static final int MAX_MESSAGES_PER_PULL = 10;
    
    // Add a counter to track execution times
    private final AtomicLong lastExecutionTime = new AtomicLong(0);

    private final EmailConsumer emailConsumer;
    private final PubSubTemplate pubSubTemplate;
    private final MailgunEmailConsumer mailgunEmailConsumer;

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;
    
    @Value("${pubsub.pull.interval-ms:15000}")
    private long pullIntervalMs;

    public PubSubListenerService(EmailConsumer emailConsumer, 
                                PubSubTemplate pubSubTemplate,
                                @org.springframework.beans.factory.annotation.Autowired(required = false) 
                                MailgunEmailConsumer mailgunEmailConsumer) {
        this.emailConsumer = emailConsumer;
        this.pubSubTemplate = pubSubTemplate;
        this.mailgunEmailConsumer = mailgunEmailConsumer;
        
        logger.info("Initialized PubSubListenerService with {} email consumer",
                mailgunEmailConsumer != null ? "Mailgun" : "standard");
    }

    /**
     * Pull messages at the configured interval
     * Using fixedDelayString to read from properties and ensure only one execution at a time
     */
    @Scheduled(fixedDelayString = "${pubsub.pull.interval-ms:15000}")
    public void pullMessages() {
        long now = System.currentTimeMillis();
        long last = lastExecutionTime.getAndSet(now);
        
        if (last > 0) {
            logger.debug("Time since last execution: {} ms (target: {} ms)", now - last, pullIntervalMs);
        }
        
        logger.info("Pulling messages from subscription: {}", subscriptionName);

        List<AcknowledgeablePubsubMessage> messages;
        try {
            // Synchronously pull messages, with a maximum of 10 messages per pull
            messages = pubSubTemplate.pull(subscriptionName, MAX_MESSAGES_PER_PULL, true);
        } catch (Exception e) {
            logger.error("Error pulling messages from subscription: {}", e.getMessage(), e);
            return;
        }

        if (messages.isEmpty()) {
            logger.debug("No messages found in the subscription.");
            return;
        }

        logger.info("Received {} message(s).", messages.size());

        // Process each message
        for (AcknowledgeablePubsubMessage message : messages) {
            PubsubMessage pubsubMessage = message.getPubsubMessage();
            String messageId = pubsubMessage.getMessageId();
            
            logger.info("Processing message ID: {}", messageId);
            
            // Use Mailgun consumer if available, otherwise fall back to standard consumer
            try {
                if (mailgunEmailConsumer != null) {
                    logger.info("Using Mailgun consumer for message: {}", messageId);
                    mailgunEmailConsumer.processMessageAsync(message);
                } else {
                    logger.info("Using standard consumer for message: {}", messageId);
                    emailConsumer.processMessageAsync(message);
                }
            } catch (Exception e) {
                logger.error("Error dispatching message: {}. Error: {}", messageId, e.getMessage(), e);
                try {
                    message.nack();
                    logger.info("Message nacked due to dispatch error: {}", messageId);
                } catch (Exception nackEx) {
                    logger.error("Failed to nack message: {}. Error: {}", 
                            messageId, nackEx.getMessage(), nackEx);
                }
            }
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