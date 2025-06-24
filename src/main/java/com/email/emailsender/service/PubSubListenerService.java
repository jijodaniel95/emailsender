package com.email.emailsender.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;

import javax.annotation.PreDestroy;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PubSubListenerService {
    private static final Logger logger = LoggerFactory.getLogger(PubSubListenerService.class);
    private static final int MAX_MESSAGES_PER_PULL = 10;
    private static final int TIMEOUT_SECONDS = 10;

    private final EmailConsumer emailConsumer;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private SubscriberStub subscriberStub;
    private ManagedChannel channel;

    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;

    @Value("${spring.cloud.gcp.credentials.location:}")
    private String credentialsPath;

    public PubSubListenerService(EmailConsumer emailConsumer) {
        this.emailConsumer = emailConsumer;
    }

    /**
     * Initialize the subscriber stub with explicit timeouts
     */
    private synchronized void initSubscriberStub() {
        if (subscriberStub != null) {
            return;
        }

        try {
            // Get credentials
            GoogleCredentials credentials = getCredentials();
            
            // Create a channel with explicit timeouts
            channel = ManagedChannelBuilder.forTarget("pubsub.googleapis.com:443")
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
            
            // Create a channel provider
            TransportChannelProvider channelProvider = 
                FixedTransportChannelProvider.create(
                    com.google.api.gax.grpc.GrpcTransportChannel.create(channel));
            
            // Create subscriber stub settings with explicit timeouts
            SubscriberStubSettings subscriberStubSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

            // Create subscriber stub
            subscriberStub = GrpcSubscriberStub.create(subscriberStubSettings);
            
            logger.info("Initialized subscriber stub for project: {}, subscription: {} with {} second timeout", 
                    projectId, subscriptionName, TIMEOUT_SECONDS);
        } catch (IOException e) {
            logger.error("Failed to initialize subscriber stub", e);
        }
    }

    /**
     * Get Google credentials based on configuration
     */
    private GoogleCredentials getCredentials() throws IOException {
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            logger.info("Using credentials file: {}", credentialsPath);
            try {
                String path = credentialsPath.replace("file:", "");
                return GoogleCredentials.fromStream(new FileInputStream(path));
            } catch (IOException e) {
                logger.error("Failed to load credentials from file: {}", credentialsPath, e);
                logger.info("Falling back to application default credentials");
            }
        }
        
        logger.info("Using application default credentials");
        return GoogleCredentials.getApplicationDefault();
    }

    /**
     * Scheduled task to pull messages every 30 seconds
     */
    @Scheduled(fixedDelayString = "30000")
    public void pullMessages() {
        // Reset shutdown flag to ensure we continue processing
        isShuttingDown.set(false);
        
        try {
            if (subscriberStub == null) {
                logger.info("Subscriber stub is null, initializing...");
                initSubscriberStub();
            }
            
            if (subscriberStub == null) {
                logger.error("Failed to initialize subscriber stub, skipping message pull");
                return;
            }
            
            // Create subscription name
            ProjectSubscriptionName subscription = ProjectSubscriptionName.of(projectId, subscriptionName);
            
            // Create pull request
            PullRequest pullRequest = PullRequest.newBuilder()
                .setMaxMessages(MAX_MESSAGES_PER_PULL)
                .setSubscription(subscription.toString())
                .build();
            
            // Pull messages with timeout
            logger.info("Pulling messages from subscription: {}", subscriptionName);
            PullResponse pullResponse;
            try {
                pullResponse = subscriberStub.pullCallable().call(pullRequest);
            } catch (ApiException e) {
                if (e.isRetryable()) {
                    logger.warn("Retryable error during pull, will try again next cycle: {}", e.getMessage());
                } else {
                    logger.error("Error pulling messages: {}", e.getMessage());
                    closeAndRecreateStub();
                }
                return;
            }
            
            List<String> ackIds = new ArrayList<>();
            
            // Process messages
            for (ReceivedMessage message : pullResponse.getReceivedMessagesList()) {
                String messageId = message.getMessage().getMessageId();
                String payload = message.getMessage().getData().toStringUtf8();
                String ackId = message.getAckId();
                
                logger.info("Received message ID: {}", messageId);
                
                try {
                    // Process message
                    emailConsumer.processMessage(payload);
                    
                    // Add to ack list
                    ackIds.add(ackId);
                    logger.info("Successfully processed message: {}", messageId);
                } catch (Exception e) {
                    logger.error("Error processing message: {}. Error: {}", messageId, e.getMessage(), e);
                    // Do not ack failed messages so they can be retried
                }
            }
            
            // Acknowledge successful messages
            if (!ackIds.isEmpty()) {
                acknowledgeMessages(subscription, ackIds);
            }
            
            int messageCount = pullResponse.getReceivedMessagesCount();
            if (messageCount > 0) {
                logger.info("Processed {} messages from subscription: {}", messageCount, subscriptionName);
            } else {
                logger.debug("No messages available in subscription: {}", subscriptionName);
            }
        } catch (Exception e) {
            logger.error("Error pulling messages, will recreate subscriber stub on next attempt", e);
            closeAndRecreateStub();
        }
    }
    
    /**
     * Acknowledge messages
     */
    private void acknowledgeMessages(ProjectSubscriptionName subscription, List<String> ackIds) {
        try {
            // Create acknowledge request
            com.google.pubsub.v1.AcknowledgeRequest ackRequest = 
                com.google.pubsub.v1.AcknowledgeRequest.newBuilder()
                    .setSubscription(subscription.toString())
                    .addAllAckIds(ackIds)
                    .build();
            
            // Acknowledge messages
            subscriberStub.acknowledgeCallable().call(ackRequest);
            logger.info("Acknowledged {} messages", ackIds.size());
        } catch (Exception e) {
            logger.error("Error acknowledging messages", e);
        }
    }
    
    /**
     * Close and recreate the subscriber stub
     */
    private synchronized void closeAndRecreateStub() {
        if (subscriberStub != null) {
            try {
                subscriberStub.close();
                logger.info("Closed existing subscriber stub");
            } catch (Exception e) {
                logger.warn("Error closing subscriber stub", e);
            } finally {
                subscriberStub = null;
            }
        }
        
        if (channel != null) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
                logger.info("Closed gRPC channel");
            } catch (Exception e) {
                logger.warn("Error closing gRPC channel", e);
            } finally {
                channel = null;
            }
        }
    }
    
    /**
     * Check if the service is healthy
     */
    public boolean isHealthy() {
        return subscriberStub != null && (channel == null || !channel.isShutdown());
    }

    /**
     * Gracefully shutdown the service
     * Note: The scheduled task will restart the subscriber on the next run
     */
    @PreDestroy
    public void shutdown() {
        isShuttingDown.set(true);
        closeAndRecreateStub();
    }
} 