package com.email.emailsender.service;

import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;

@Service
public class PubSubListenerService {
    private static final Logger logger = LoggerFactory.getLogger(PubSubListenerService.class);
    private static final int MAX_MESSAGES_PER_PULL = 10;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    // Add a counter to track execution times
    private final AtomicLong lastExecutionTime = new AtomicLong(0);

    private final EmailConsumer emailConsumer;
    private final PubSubTemplate pubSubTemplate;
    private final MailgunEmailConsumer mailgunEmailConsumer;
    private final RetrySettings retrySettings;

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;
    
    @Value("${pubsub.pull.interval-ms:900000}")
    private long pullIntervalMs;
    
    @Value("${pubsub.pull.timeout-seconds:60}")
    private int pullTimeoutSeconds;

    public PubSubListenerService(EmailConsumer emailConsumer, 
                                PubSubTemplate pubSubTemplate,
                                @org.springframework.beans.factory.annotation.Autowired(required = false) 
                                MailgunEmailConsumer mailgunEmailConsumer,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                RetrySettings subscriberRetrySettings) {
        this.emailConsumer = emailConsumer;
        this.pubSubTemplate = pubSubTemplate;
        this.mailgunEmailConsumer = mailgunEmailConsumer;
        this.retrySettings = subscriberRetrySettings;
        
        logger.info("Initialized PubSubListenerService with {} email consumer",
                mailgunEmailConsumer != null ? "Mailgun" : "standard");
    }
    
    @PostConstruct
    public void init() {
        logger.info("PubSubListenerService initialized with pull interval: {} ms, timeout: {} seconds", 
                pullIntervalMs, pullTimeoutSeconds);
    }

    /**
     * Pull messages at the configured interval
     * Using fixedDelayString to read from properties and ensure only one execution at a time
     */
    @Scheduled(fixedDelayString = "${pubsub.pull.interval-ms:900000}")
    public void pullMessages() {
        long now = System.currentTimeMillis();
        long last = lastExecutionTime.getAndSet(now);
        
        if (last > 0) {
            logger.info("Time since last execution: {} ms (target: {} ms)", now - last, pullIntervalMs);
        } else {
            logger.info("First execution of scheduled pull at: {}", now);
        }
        
        logger.info("Pulling messages from subscription: {}", subscriptionName);

        List<AcknowledgeablePubsubMessage> messages = pullWithRetry();
        
        if (messages == null || messages.isEmpty()) {
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
     * Pull messages with retry logic for DeadlineExceededException
     * Uses exponential backoff for retries
     */
    private List<AcknowledgeablePubsubMessage> pullWithRetry() {
        int attempt = 0;
        long backoffMs = 1000; // Start with 1 second
        
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                // Synchronously pull messages, with a maximum of 10 messages per pull
                return pubSubTemplate.pull(subscriptionName, MAX_MESSAGES_PER_PULL, true);
            } catch (DeadlineExceededException e) {
                attempt++;
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    logger.error("Failed to pull messages after {} attempts. Last error: {}", 
                            MAX_RETRY_ATTEMPTS, e.getMessage());
                    return Collections.emptyList();
                }
                
                logger.warn("Deadline exceeded on attempt {}. Retrying in {} ms. Error: {}", 
                        attempt, backoffMs, e.getMessage());
                
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                    // Exponential backoff - double the wait time for next attempt
                    backoffMs *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.error("Retry interrupted", ie);
                    return Collections.emptyList();
                }
            } catch (Exception e) {
                logger.error("Error pulling messages from subscription: {}", e.getMessage(), e);
                return Collections.emptyList();
            }
        }
        
        return Collections.emptyList();
    }

    /**
     * Check if the service is healthy
     */
    public boolean isHealthy() {
        try {
            // Try to pull 0 messages to check connectivity
            pubSubTemplate.pull(subscriptionName, 0, true);
            return true;
        } catch (DeadlineExceededException e) {
            // Timeout is not necessarily an indication of unhealthiness
            logger.warn("Health check timeout but service may still be operational: {}", e.getMessage());
            return true;
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return false;
        }
    }
} 