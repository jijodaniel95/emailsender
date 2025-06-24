package com.email.emailsender.config;

import com.email.emailsender.service.EmailConsumer;
import com.google.cloud.spring.pubsub.core.subscriber.PubSubSubscriberTemplate;
import com.google.cloud.spring.pubsub.integration.AckMode;
import com.google.cloud.spring.pubsub.integration.inbound.PubSubInboundChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.integration.core.ErrorMessagePublisher;

@EnableIntegration
@Configuration
public class PubSubConfig {

    private static final Logger logger = LoggerFactory.getLogger(PubSubConfig.class);

    @Value("${pubsub.subscription.name}")
    private String subscriptionName;

    @Bean
    public MessageChannel inputMessageChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel errorChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "errorChannel")
    public MessageHandler errorHandler() {
        return message -> {
            logger.error("Error processing message: {}", message.getPayload());
            Throwable error = (Throwable) message.getPayload();
            logger.error("Error details:", error);
        };
    }

    @Bean
    public PubSubInboundChannelAdapter messageChannelAdapter(
            @Qualifier("inputMessageChannel") MessageChannel inputChannel,
            @Qualifier("errorChannel") MessageChannel errorChannel,
            PubSubSubscriberTemplate pubSubTemplate
    ) {
        logger.info("Initializing Pub/Sub adapter for subscription: {}", subscriptionName);
        
        PubSubInboundChannelAdapter adapter =
                new PubSubInboundChannelAdapter(pubSubTemplate, subscriptionName);

        adapter.setOutputChannel(inputChannel);
        adapter.setErrorChannel(errorChannel);
        adapter.setAckMode(AckMode.AUTO);
        adapter.setPayloadType(String.class);

        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "inputMessageChannel")
    public MessageHandler messageReceiver(EmailConsumer consumer) {
        return message -> {
            try {
                logger.debug("Received message: {}", message.getPayload());
                consumer.processMessage((String) message.getPayload());
                logger.debug("Successfully processed message");
            } catch (Exception e) {
                logger.error("Error processing message: {}", message.getPayload(), e);
                throw e; // Rethrow to trigger retry if configured
            }
        };
    }
}