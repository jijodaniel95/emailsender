package com.email.emailsender.service;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PubSubListenerService {
    private static final Logger logger = LoggerFactory.getLogger(PubSubListenerService.class);

    private final EmailConsumer emailConsumer;
    private Subscriber subscriber;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;

    @Value("${spring.cloud.gcp.credentials.location:}")
    private String credentialsPath;
    
    @Value("${pubsub.verify.subscription:false}")
    private boolean verifySubscription;
    
    @Value("${pubsub.flow-control.max-outstanding-element-count:1000}")
    private long maxOutstandingElementCount;
    
    @Value("${pubsub.flow-control.max-outstanding-request-bytes:100000000}")
    private long maxOutstandingRequestBytes;
    
    @Value("${pubsub.subscriber.await-running-timeout:60}")
    private int awaitRunningTimeoutSeconds;

    public PubSubListenerService(EmailConsumer emailConsumer) {
        this.emailConsumer = emailConsumer;
    }

    /**
     * Get Google credentials based on configuration
     */
    private CredentialsProvider getCredentialsProvider() {
        try {
            GoogleCredentials credentials;
            
            if (credentialsPath != null && !credentialsPath.isEmpty()) {
                logger.info("Using credentials file: {}", credentialsPath);
                try {
                    String path = credentialsPath.replace("file:", "");
                    credentials = GoogleCredentials.fromStream(new FileInputStream(path));
                    return FixedCredentialsProvider.create(credentials);
                } catch (IOException e) {
                    logger.error("Failed to load credentials from file: {}", credentialsPath, e);
                    logger.info("Falling back to application default credentials");
                }
            }
            
            logger.info("Using application default credentials");
            credentials = GoogleCredentials.getApplicationDefault();
            return FixedCredentialsProvider.create(credentials);
        } catch (IOException e) {
            logger.error("Failed to load any credentials, using no credentials", e);
            return NoCredentialsProvider.create();
        }
    }

    /**
     * Verify that the subscription exists
     */
    private boolean verifySubscriptionExists(ProjectSubscriptionName subscription, CredentialsProvider credentialsProvider) {
        try (SubscriptionAdminClient subscriptionAdminClient = SubscriptionAdminClient.create(
                SubscriptionAdminSettings.newBuilder()
                    .setCredentialsProvider(credentialsProvider)
                    .build())) {
            
            GetSubscriptionRequest request = GetSubscriptionRequest.newBuilder()
                .setSubscription(subscription.toString())
                .build();
                
            subscriptionAdminClient.getSubscription(request);
            logger.info("Subscription verified: {}", subscription);
            return true;
        } catch (Exception e) {
            logger.error("Failed to verify subscription exists: {}", subscription, e);
            return false;
        }
    }

    /**
     * Create a message receiver for processing Pub/Sub messages
     */
    private MessageReceiver createMessageReceiver() {
        return (PubsubMessage message, AckReplyConsumer consumer) -> {
            String messageId = message.getMessageId();
            logger.info("Received message ID: {}", messageId);
            
            try {
                String payload = message.getData().toStringUtf8();
                emailConsumer.processMessage(payload);
                consumer.ack();
                logger.info("Successfully processed and acknowledged message: {}", messageId);
            } catch (Exception e) {
                logger.error("Error processing message: {}. Error: {}", messageId, e.getMessage(), e);
                consumer.nack();
            }
        };
    }

    /**
     * Initialize and start the Pub/Sub subscriber after the application is fully started
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application is ready, initializing Pub/Sub subscriber");
        startSubscriber();
    }
    
    /**
     * Scheduled health check that ensures the subscriber is running
     */
    @Scheduled(fixedDelayString = "${pubsub.watchdog.interval-ms:15000}")
    public void checkSubscriberHealth() {
        if (isShuttingDown.get()) {
            return;
        }
        
        try {
            if (subscriber == null || !isRunning.get() || !subscriber.isRunning()) {
                logger.warn("Scheduled check detected subscriber is not running. Current state: {}", 
                        subscriber != null ? subscriber.state() : "NULL");
                startSubscriber();
            } else {
                logger.debug("Scheduled check: Subscriber is running normally");
            }
        } catch (Exception e) {
            logger.error("Error in subscriber scheduled check", e);
        }
    }

    /**
     * Initialize and start the Pub/Sub subscriber
     * This method handles cleanup of existing subscribers and creates a new one if needed
     */
    public synchronized void startSubscriber() {
        if (isShuttingDown.get()) {
            logger.info("Service is shutting down, not starting subscriber");
            return;
        }
        
        if (isRunning.get() && subscriber != null && subscriber.isRunning()) {
            logger.info("Subscriber is already running, skipping initialization");
            return;
        }
        
        // Clean up any existing subscriber
        if (subscriber != null) {
            try {
                if (subscriber.isRunning()) {
                    logger.info("Stopping existing subscriber before creating a new one");
                    subscriber.stopAsync();
                    try {
                        subscriber.awaitTerminated(5, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        logger.warn("Timed out waiting for subscriber to stop", e);
                    }
                }
            } catch (Exception e) {
                logger.warn("Error stopping existing subscriber", e);
            } finally {
                subscriber = null;
            }
        }
        
        try {
            logger.info("Starting Pub/Sub subscriber for project: {}, subscription: {}", projectId, subscriptionName);
            
            // Get credentials and create subscription name
            CredentialsProvider credentialsProvider = getCredentialsProvider();
            ProjectSubscriptionName subscription = ProjectSubscriptionName.of(projectId, subscriptionName);
            
            // Verify subscription if configured
            if (verifySubscription && !verifySubscriptionExists(subscription, credentialsProvider)) {
                logger.warn("Subscription verification failed, but continuing with subscriber setup");
            }

            // Build the subscriber
            subscriber = Subscriber.newBuilder(subscription, createMessageReceiver())
                .setCredentialsProvider(credentialsProvider)
                .setFlowControlSettings(
                    com.google.api.gax.batching.FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount(maxOutstandingElementCount)
                        .setMaxOutstandingRequestBytes(maxOutstandingRequestBytes)
                        .build()
                )
                .build();

            // Add state change listeners
            subscriber.addListener(
                new Subscriber.Listener() {
                    @Override
                    public void failed(Subscriber.State from, Throwable failure) {
                        logger.error("Subscriber failed: {} -> FAILED", from, failure);
                        isRunning.set(false);
                        
                        if (!isShuttingDown.get()) {
                            scheduleRestart(5);
                        }
                    }
                    
                    @Override
                    public void running() {
                        logger.info("Subscriber is now running and ready to receive messages");
                        isRunning.set(true);
                    }
                    
                    @Override
                    public void terminated(Subscriber.State from) {
                        logger.warn("Subscriber terminated from state: {}", from);
                        isRunning.set(false);
                        
                        if (!isShuttingDown.get()) {
                            scheduleRestart(3);
                        }
                    }
                },
                command -> {
                    try {
                        command.run();
                    } catch (Exception e) {
                        logger.error("Error in subscriber listener", e);
                    }
                }
            );

            // Start the subscriber and wait for it to be running
            logger.info("Starting subscriber for subscription: {}", subscriptionName);
            subscriber.startAsync();
            
            try {
                logger.info("Waiting for subscriber to transition to RUNNING state");
                subscriber.awaitRunning(awaitRunningTimeoutSeconds, TimeUnit.SECONDS);
                logger.info("Subscriber is now in RUNNING state and actively pulling messages");
            } catch (TimeoutException e) {
                logger.error("Subscriber did not transition to RUNNING state within timeout", e);
            } catch (IllegalStateException e) {
                logger.error("Subscriber failed to start: {}", e.getMessage(), e);
                scheduleRestart(5);
                return;
            }
            
            logger.info("Pub/Sub subscriber initialization completed");
        } catch (Exception e) {
            logger.error("Failed to create subscriber", e);
        }
    }
    
    /**
     * Schedule a restart of the subscriber after a delay
     */
    private void scheduleRestart(int delaySeconds) {
        new Thread(() -> {
            try {
                logger.info("Waiting {} seconds before attempting to restart subscriber", delaySeconds);
                Thread.sleep(delaySeconds * 1000);
                startSubscriber();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting to restart subscriber", e);
            }
        }).start();
    }
    
    /**
     * Check if the subscriber is healthy
     */
    public boolean isHealthy() {
        return subscriber != null && isRunning.get() && subscriber.isRunning();
    }

    /**
     * Gracefully shutdown the subscriber
     */
    @PreDestroy
    public void stopSubscriber() {
        isShuttingDown.set(true);
        
        if (subscriber != null) {
            try {
                logger.info("Shutting down Pub/Sub subscriber...");
                subscriber.stopAsync();
                try {
                    subscriber.awaitTerminated(30, TimeUnit.SECONDS);
                    logger.info("Subscriber stopped successfully");
                } catch (TimeoutException e) {
                    logger.error("Failed to stop subscriber gracefully within timeout", e);
                }
            } catch (Exception e) {
                logger.error("Error while stopping subscriber", e);
            } finally {
                isRunning.set(false);
                subscriber = null;
            }
        }
    }
} 