package com.example.frontend.controller.dashboard;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import com.example.frontend.service.ApiClient;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.lang.reflect.Type;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * STOMP/WebSocket 실시간 대시보드 모듈.
 *
 * <ul>
 *   <li>/topic/dashboard — 화면 갱신 콜백</li>
 *   <li>/topic/alerts    — 이상 감지 알림 콜백</li>
 * </ul>
 *
 * 연결 실패 또는 세션 종료 시 30초 후 자동 재연결.
 */
public class DashboardRealtimeModule {

    private static final Logger log = LoggerFactory.getLogger(DashboardRealtimeModule.class);

    private static final int RECONNECT_DELAY_SEC = 30;

    private final String wsUrl;
    private volatile ScheduledExecutorService scheduler = createScheduler();

    private WebSocketStompClient stompClient;
    private volatile StompSession activeSession;  // volatile: 멀티 스레드 접근 안전성
    private volatile boolean stopped = false;  // volatile: 명시적으로 추가
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private Runnable onDashboardMessage;
    private Consumer<String> onAlertMessage;
    private Runnable onConnectionLost;
    private Runnable onConnected;

    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    public DashboardRealtimeModule() {
        this(com.example.frontend.service.ApiClient.getInstance().getWsUrl());
    }

    public DashboardRealtimeModule(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    /**
     * WebSocket에 연결하고 메시지 핸들러를 등록합니다.
     *
     * @param onDashboard 대시보드 갱신 시 호출 (JavaFX 스레드로 전달됨)
     * @param onAlert     알림 메시지 수신 시 호출 (payload = JSON 문자열)
     */
    public void start(Runnable onDashboard, Consumer<String> onAlert) {
        start(onDashboard, onAlert, null);
    }

    public void start(Runnable onDashboard, Consumer<String> onAlert, Runnable onConnectionLost) {
        this.onDashboardMessage = onDashboard;
        this.onAlertMessage = onAlert;
        this.onConnectionLost = onConnectionLost;
        stopped = false;
        // stop() 후 다시 start() 시 스케줄러 재생성
        if (scheduler.isShutdown()) {
            scheduler = createScheduler();
        }
        if (activeSession != null && activeSession.isConnected()) {
            log.debug("[WS] 이미 연결된 세션이 있어 재사용합니다: {}", wsUrl);
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            log.debug("[WS] 연결 진행 중이어서 중복 start를 스킵합니다: {}", wsUrl);
            return;
        }
        doConnect();
    }

    /** 하위 호환 - 대시보드 콜백만 등록할 경우 */
    public WebSocketStompClient connect(Runnable onDashboard) {
        start(onDashboard, null);
        return stompClient;
    }

    /** WebSocket 연결 종료 및 재연결 중단. */
    public void stop() {
        stopped = true;
        scheduler.shutdownNow();
        if (activeSession != null && activeSession.isConnected()) {
            activeSession.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    // ── 내부 구현 ──────────────────────────────────────────────────────────

    private static ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    private void doConnect() {
        if (stopped) return;
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        stompClient = new WebSocketStompClient(wsClient);
        stompClient.setMessageConverter(new StringMessageConverter());

        // Attach Authorization header if API client has a bearer token
        StompHeaders connectHeaders = new StompHeaders();
        try {
            String token = ApiClient.getInstance().getBearerToken();
            if (token != null && !token.isBlank()) {
                connectHeaders.add("Authorization", "Bearer " + token);
            }
        } catch (Exception e) {
            // best-effort: do not fail connect if token retrieval fails
            log.debug("Failed to retrieve bearer token for WS connect: {}", e.getMessage());
        }

        stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {

            @Override
            public void afterConnected(StompSession session, StompHeaders headers) {
                activeSession = session;
                connecting.set(false);
                log.info("[WS] Connected to {}", wsUrl);
                subscribeTopics(session);
                // Notify listener that connection is established
                if (onConnected != null) {
                    Platform.runLater(onConnected);
                }
            }

            @Override
            public void handleTransportError(StompSession session, Throwable ex) {
                connecting.set(false);
                log.warn("[WS] Transport error", ex);
                notifyConnectionLost();
                scheduleReconnect();
            }

            @Override
            public void handleException(StompSession session, StompCommand cmd,
                                        StompHeaders headers, byte[] payload, Throwable ex) {
                log.warn("[WS] STOMP exception", ex);
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // ERROR frames
                connecting.set(false);
                log.warn("[WS] ERROR frame: {}", payload);
                notifyConnectionLost();
                scheduleReconnect();
            }
        });
    }

    private void notifyConnectionLost() {
        if (onConnectionLost == null) {
            return;
        }
        Platform.runLater(onConnectionLost);
    }

    private void subscribeTopics(StompSession session) {
        session.subscribe("/topic/dashboard", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) { return String.class; }

            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                if (onDashboardMessage != null) {
                    Platform.runLater(onDashboardMessage);
                }
            }
        });

        session.subscribe("/topic/alerts", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) { return String.class; }

            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                if (onAlertMessage != null) {
                    String msg = payload == null ? "" : payload.toString();
                    Platform.runLater(() -> onAlertMessage.accept(msg));
                }
            }
        });
    }

    private void scheduleReconnect() {
        if (stopped) return;
        if (!reconnectPending.compareAndSet(false, true)) {
            log.debug("[WS] 재연결 이미 예약됨, 중복 예약 스킵");
            return;
        }
        log.info("[WS] 재연결 예약 ({}초 후)...", RECONNECT_DELAY_SEC);
        scheduler.schedule(() -> {
            reconnectPending.set(false);
            connecting.set(false);
            doConnect();
        }, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
    }
}
