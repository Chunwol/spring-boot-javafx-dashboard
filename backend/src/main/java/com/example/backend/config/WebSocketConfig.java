package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.runtime.websocket.inbound-core-pool-size:4}")
    private int inboundCorePoolSize;

    @Value("${app.runtime.websocket.inbound-max-pool-size:16}")
    private int inboundMaxPoolSize;

    @Value("${app.runtime.websocket.inbound-queue-capacity:2000}")
    private int inboundQueueCapacity;

    @Value("${app.runtime.websocket.outbound-core-pool-size:4}")
    private int outboundCorePoolSize;

    @Value("${app.runtime.websocket.outbound-max-pool-size:16}")
    private int outboundMaxPoolSize;

    @Value("${app.runtime.websocket.outbound-queue-capacity:2000}")
    private int outboundQueueCapacity;

    @Value("${app.runtime.websocket.message-size-limit-bytes:1048576}")
    private int messageSizeLimitBytes;

    @Value("${app.runtime.websocket.send-buffer-size-limit-bytes:2097152}")
    private int sendBufferSizeLimitBytes;

    @Value("${app.runtime.websocket.send-time-limit-ms:20000}")
    private int sendTimeLimitMs;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(Math.max(2, inboundCorePoolSize))
                .maxPoolSize(Math.max(inboundCorePoolSize, inboundMaxPoolSize))
                .queueCapacity(Math.max(100, inboundQueueCapacity));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(Math.max(2, outboundCorePoolSize))
                .maxPoolSize(Math.max(outboundCorePoolSize, outboundMaxPoolSize))
                .queueCapacity(Math.max(100, outboundQueueCapacity));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(Math.max(256 * 1024, messageSizeLimitBytes));
        registry.setSendBufferSizeLimit(Math.max(512 * 1024, sendBufferSizeLimitBytes));
        registry.setSendTimeLimit(Math.max(5000, sendTimeLimitMs));
    }
}
