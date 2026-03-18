package com.example.frontend.controller.dashboard;

import javafx.application.Platform;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private static final int RECONNECT_DELAY_SEC = 30;

    private final String wsUrl;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private WebSocketStompClient stompClient;
    private StompSession activeSession;
    private volatile boolean stopped = false;

    private Runnable onDashboardMessage;
    private Consumer<String> onAlertMessage;

    public DashboardRealtimeModule() {
        this("ws://localhost:8080/ws/websocket");
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
        this.onDashboardMessage = onDashboard;
        this.onAlertMessage = onAlert;
        stopped = false;
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

    private void doConnect() {
        if (stopped) return;
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        stompClient = new WebSocketStompClient(wsClient);
        stompClient.setMessageConverter(new StringMessageConverter());

        stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {

            @Override
            public void afterConnected(StompSession session, StompHeaders headers) {
                activeSession = session;
                System.out.println("[WS] Connected to " + wsUrl);
                subscribeTopics(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable ex) {
                System.err.println("[WS] Transport error: " + ex.getMessage());
                scheduleReconnect();
            }

            @Override
            public void handleException(StompSession session, StompCommand cmd,
                                        StompHeaders headers, byte[] payload, Throwable ex) {
                System.err.println("[WS] STOMP exception: " + ex.getMessage());
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // ERROR frames
                System.err.println("[WS] ERROR frame: " + payload);
                scheduleReconnect();
            }
        });
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
        System.out.println("[WS] 재연결 예약 (" + RECONNECT_DELAY_SEC + "초 후)...");
        scheduler.schedule(this::doConnect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
    }
}
