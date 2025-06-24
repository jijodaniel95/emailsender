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
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

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
    
    @Value("${pubsub.subscriber.await-running-timeout:30}")
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
                // Extract message data
                String payload = message.getData().toStringUtf8();
                
                // Process the message
                emailConsumer.processMessage(payload);
                
                // Acknowledge successful processing
                consumer.ack();
                logger.info("Successfully processed and acknowledged message: {}", messageId);
            } catch (Exception e) {
                logger.error("Error processing message: {}. Error: {}", messageId, e.getMessage(), e);
                // Negative acknowledge to allow redelivery
                consumer.nack();
            }
        };
    }

    /**
     * Initialize and start the Pub/Sub subscriber after the application is fully started
     * This ensures that Cloud Run has properly initialized before we start consuming messages
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application is ready, initializing Pub/Sub subscriber");
        startSubscriber();
    }

    /**
     * Initialize and start the Pub/Sub subscriber
     */
    public synchronized void startSubscriber() {
        if (isInitialized.get()) {
            logger.info("Subscriber is already initialized, skipping initialization");
            return;
        }
        
        try {
            logger.info("Starting Pub/Sub subscriber for project: {}, subscription: {}", projectId, subscriptionName);
            
            // Get credentials provider
            CredentialsProvider credentialsProvider = getCredentialsProvider();
            
            // Create subscription name
            ProjectSubscriptionName subscription = ProjectSubscriptionName.of(projectId, subscriptionName);
            
            // Verify subscription if configured to do so
            if (verifySubscription && !verifySubscriptionExists(subscription, credentialsProvider)) {
                logger.warn("Subscription verification failed, but continuing with subscriber setup");
            }

            // Create message receiver
            MessageReceiver receiver = createMessageReceiver();

            // Build the subscriber with flow control settings
            Subscriber.Builder builder = Subscriber.newBuilder(subscription, receiver)
                .setCredentialsProvider(credentialsProvider)
                .setFlowControlSettings(
                    com.google.api.gax.batching.FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount(maxOutstandingElementCount)
                        .setMaxOutstandingRequestBytes(maxOutstandingRequestBytes)
                        .build()
                );

            // Create the subscriber
            subscriber = builder.build();

            // Add listener for subscriber state changes
            subscriber.addListener(
                new Subscriber.Listener() {
                    @Override
                    public void failed(Subscriber.State from, Throwable failure) {
                        logger.error("Subscriber failed: {} -> FAILED", from, failure);
                        isRunning.set(false);
                        
                        // Attempt to restart the subscriber after a delay
                        new Thread(() -> {
                            try {
                                logger.info("Waiting 10 seconds before attempting to restart subscriber");
                                Thread.sleep(10000);
                                restartSubscriber();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                logger.error("Interrupted while waiting to restart subscriber", e);
                            }
                        }).start();
                    }
                    
                    @Override
                    public void running() {
                        logger.info("Subscriber is now running and ready to receive messages");
                        isRunning.set(true);
                    }
                },
                command -> command.run()
            );

            // Start the subscriber and wait for it to be running
            logger.info("Starting subscriber async for subscription: {}", subscriptionName);
            subscriber.startAsync();
            
            // Wait for the subscriber to be running
            try {
                logger.info("Waiting for subscriber to transition to RUNNING state");
                subscriber.awaitRunning(awaitRunningTimeoutSeconds, TimeUnit.SECONDS);
                logger.info("Subscriber is now in RUNNING state and actively pulling messages");
            } catch (TimeoutException e) {
                logger.error("Subscriber did not transition to RUNNING state within timeout", e);
            }
            
            isInitialized.set(true);
            logger.info("Pub/Sub subscriber initialization completed");
        } catch (Exception e) {
            logger.error("Failed to create subscriber", e);
            throw new RuntimeException("Failed to initialize Pub/Sub subscriber", e);
        }
    }
    
    /**
     * Restart the subscriber if it has failed
     */
    public void restartSubscriber() {
        if (isRunning.get()) {
            logger.info("Subscriber is already running, no need to restart");
            return;
        }
        
        logger.info("Attempting to restart subscriber");
        
        // Clean up any existing subscriber
        if (subscriber != null) {
            try {
                subscriber.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);
                isInitialized.set(false);
            } catch (Exception e) {
                logger.warn("Error stopping existing subscriber during restart", e);
            }
        }
        
        // Start a new subscriber
        startSubscriber();
    }
    
    /**
     * Check if the subscriber is healthy
     */
    public boolean isHealthy() {
        if (subscriber == null) {
            return false;
        }
        
        return isRunning.get() && subscriber.isRunning();
    }

    /**
     * Gracefully shutdown the subscriber
     */
    @PreDestroy
    public void stopSubscriber() {
        if (subscriber != null) {
            try {
                logger.info("Shutting down Pub/Sub subscriber...");
                subscriber.stopAsync().awaitTerminated(30, TimeUnit.SECONDS);
                logger.info("Subscriber stopped successfully");
            } catch (TimeoutException e) {
                logger.error("Failed to stop subscriber gracefully within timeout", e);
            } catch (Exception e) {
                logger.error("Error while stopping subscriber", e);
            } finally {
                isRunning.set(false);
                isInitialized.set(false);
            }
        }
    }
} 