package com.example.backend.config;

import com.example.backend.remotecontrol.VncRelayHandshakeInterceptor;
import com.example.backend.remotecontrol.VncRelayWebSocketHandler;
import com.example.backend.security.WebSocketAuthChannelInterceptor;
import com.example.backend.security.JwtExpiryWebSocketHandlerDecoratorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 메시지 브로커 설정.
 *
 * <p>엔드포인트 구조:
 * <ul>
 *   <li>{@code /ws} — SockJS 폴백을 포함한 WebSocket 연결 엔드포인트.
 *       브라우저 대시보드가 이 경로로 접속한다.</li>
 *   <li>{@code /topic/**} — 서버가 구독자에게 브로드캐스트하는 주제 채널
 *       (예: {@code /topic/alerts}, {@code /topic/traffic}).</li>
 *   <li>{@code /app/**} — 클라이언트에서 서버로 메시지를 보내는 접두사.</li>
 * </ul>
 *
 * <p>스레드 풀 구성 (application.yml):
 * <pre>
 * app.runtime.websocket.inbound-core-pool-size      (기본 4)
 * app.runtime.websocket.inbound-max-pool-size       (기본 16)
 * app.runtime.websocket.inbound-queue-capacity      (기본 2000)
 * app.runtime.websocket.outbound-core-pool-size     (기본 4)
 * app.runtime.websocket.outbound-max-pool-size      (기본 16)
 * app.runtime.websocket.outbound-queue-capacity     (기본 2000)
 * app.runtime.websocket.message-size-limit-bytes    (기본 1 MiB)
 * app.runtime.websocket.send-buffer-size-limit-bytes(기본 2 MiB)
 * app.runtime.websocket.send-time-limit-ms          (기본 20000 ms)
 * </pre>
 */
@Configuration
@EnableWebSocket
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private final CorsProperties corsProperties;
    private final WebSocketAuthChannelInterceptor authChannelInterceptor;
    private final VncRelayWebSocketHandler vncRelayWebSocketHandler;
    private final VncRelayHandshakeInterceptor vncRelayHandshakeInterceptor;
    private final JwtExpiryWebSocketHandlerDecoratorFactory jwtExpiryDecoratorFactory;

    public WebSocketConfig(
            CorsProperties corsProperties,
            WebSocketAuthChannelInterceptor authChannelInterceptor,
            VncRelayWebSocketHandler vncRelayWebSocketHandler,
            VncRelayHandshakeInterceptor vncRelayHandshakeInterceptor,
            JwtExpiryWebSocketHandlerDecoratorFactory jwtExpiryDecoratorFactory) {
        this.corsProperties = corsProperties;
        this.authChannelInterceptor = authChannelInterceptor;
        this.vncRelayWebSocketHandler = vncRelayWebSocketHandler;
        this.vncRelayHandshakeInterceptor = vncRelayHandshakeInterceptor;
        this.jwtExpiryDecoratorFactory = jwtExpiryDecoratorFactory;
    }

    /** 인바운드 채널(클라이언트 → 서버) 코어 스레드 수 */
    @Value("${app.runtime.websocket.inbound-core-pool-size:4}")
    private int inboundCorePoolSize;

    /** 인바운드 채널 최대 스레드 수 */
    @Value("${app.runtime.websocket.inbound-max-pool-size:16}")
    private int inboundMaxPoolSize;

    /** 인바운드 채널 작업 대기 큐 용량 */
    @Value("${app.runtime.websocket.inbound-queue-capacity:2000}")
    private int inboundQueueCapacity;

    /** 아웃바운드 채널(서버 → 클라이언트) 코어 스레드 수 */
    @Value("${app.runtime.websocket.outbound-core-pool-size:4}")
    private int outboundCorePoolSize;

    /** 아웃바운드 채널 최대 스레드 수 */
    @Value("${app.runtime.websocket.outbound-max-pool-size:16}")
    private int outboundMaxPoolSize;

    /** 아웃바운드 채널 작업 대기 큐 용량 */
    @Value("${app.runtime.websocket.outbound-queue-capacity:2000}")
    private int outboundQueueCapacity;

    /** 단일 WebSocket 메시지 최대 크기(바이트). 기본 1 MiB(1,048,576). */
    @Value("${app.runtime.websocket.message-size-limit-bytes:1048576}")
    private int messageSizeLimitBytes;

    /** 전송 버퍼 최대 크기(바이트). 기본 2 MiB(2,097,152). */
    @Value("${app.runtime.websocket.send-buffer-size-limit-bytes:2097152}")
    private int sendBufferSizeLimitBytes;

    /** 단일 메시지 전송 타임아웃(밀리초). 기본 20초. */
    @Value("${app.runtime.websocket.send-time-limit-ms:20000}")
    private int sendTimeLimitMs;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(vncRelayWebSocketHandler, "/vnc-relay/*")
                .addInterceptors(vncRelayHandshakeInterceptor)
                .setAllowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor).taskExecutor()
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
        registry.addDecoratorFactory(jwtExpiryDecoratorFactory);
        registry.setMessageSizeLimit(Math.max(256 * 1024, messageSizeLimitBytes));
        registry.setSendBufferSizeLimit(Math.max(512 * 1024, sendBufferSizeLimitBytes));
        registry.setSendTimeLimit(Math.max(5000, sendTimeLimitMs));
    }
}
