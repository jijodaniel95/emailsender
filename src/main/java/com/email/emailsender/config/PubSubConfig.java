package com.email.emailsender.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.TransportChannelProvider;
import org.threeten.bp.Duration;

@Configuration
public class PubSubConfig {
    
    @Value("${pubsub.pull.timeout-seconds:60}")
    private int pullTimeoutSeconds;
    
    /**
     * Configure timeout settings for PubSub operations
     */
    @Bean
    @ConditionalOnMissingBean(name = "subscriberRetrySettings")
    public RetrySettings subscriberRetrySettings() {
        return RetrySettings.newBuilder()
                .setInitialRetryDelay(Duration.ofMillis(250))
                .setRetryDelayMultiplier(1.5)
                .setMaxRetryDelay(Duration.ofSeconds(5))
                .setInitialRpcTimeout(Duration.ofSeconds(pullTimeoutSeconds))
                .setRpcTimeoutMultiplier(1.0)
                .setMaxRpcTimeout(Duration.ofSeconds(pullTimeoutSeconds))
                .setTotalTimeout(Duration.ofSeconds(pullTimeoutSeconds + 10))
                .build();
    }
    
    /**
     * Configure a channel provider with better connection settings
     */
    @Bean
    @ConditionalOnMissingBean(name = "channelProvider")
    public TransportChannelProvider channelProvider() {
        return InstantiatingGrpcChannelProvider.newBuilder()
                .setEndpoint("pubsub.googleapis.com:443")
                .setKeepAliveTime(Duration.ofMinutes(5))
                .setKeepAliveTimeout(Duration.ofSeconds(60))
                .setKeepAliveWithoutCalls(true)
                .build();
    }
}