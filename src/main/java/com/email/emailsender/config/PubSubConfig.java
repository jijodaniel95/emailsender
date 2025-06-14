package com.email.emailsender.config;


import com.email.emailsender.service.EmailConsumer;
import com.google.cloud.spring.pubsub.core.subscriber.PubSubSubscriberTemplate;
import com.google.cloud.spring.pubsub.integration.AckMode;
import com.google.cloud.spring.pubsub.integration.inbound.PubSubInboundChannelAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value; // Added import
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
// ... existing code ...
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
// ... existing code ...

@Configuration
public class PubSubConfig {

    @Value("${pubsub.subscription.name}") // Added
    private String subscriptionName; // Added

    @Bean
    public MessageChannel inputMessageChannel() {
        return new DirectChannel();
    }

    @Bean
    public PubSubInboundChannelAdapter messageChannelAdapter(
            @Qualifier("inputMessageChannel") MessageChannel inputChannel,
            PubSubSubscriberTemplate pubSubTemplate
    ) {
        PubSubInboundChannelAdapter adapter =
                new PubSubInboundChannelAdapter(pubSubTemplate, subscriptionName); // Changed to use variable

        adapter.setOutputChannel(inputChannel);
        adapter.setAckMode(AckMode.AUTO);
        adapter.setPayloadType(String.class);

        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "inputMessageChannel")
    public MessageHandler messageReceiver(EmailConsumer consumer) {
        return msg -> {
            consumer.processMessage((String) msg.getPayload());
        };
    }
}