package com.example.backend.service.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * STOMP WebSocket 메시지 발송 서비스.
 *
 * <p>서버 측 이벤트(PC 상태 변경, 이상 감지 등)를 구독 중인 모든 클라이언트에게
 * Pub/Sub 방식으로 실시간 푸시한다.
 *
 * <p>구독 토픽:
 * <ul>
 *   <li>{@code /topic/dashboard}: 대시보드 전체 갱신 이벤트 (PC 온라인 상태 변경 등)</li>
 *   <li>{@code /topic/alerts}: 이상 감지 알림 이벤트 (트래픽 임계치 초과 등)</li>
 * </ul>
 *
 * <p>WebSocket 설정은 {@code WebSocketConfig}에서 관리한다.
 */
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 대시보드 갱신 이벤트를 구독 중인 모든 클라이언트에게 브로드캐스트한다.
     *
     * <p>PC 상태 변경, heartbeat 수신 등 대시보드 데이터가 변경되었을 때 호출한다.
     * 프론트엔드는 {@code /topic/dashboard}를 구독하여 수신 후 화면을 갱신한다.
     *
     * @param data 발송할 페이로드 (JSON 직렬화 가능 객체)
     */
    public void sendDashboardUpdate(Object data) {
        messagingTemplate.convertAndSend("/topic/dashboard", data);
    }

    /**
     * 이상 감지 알림을 구독 중인 모든 클라이언트에게 브로드캐스트한다.
     *
     * <p>트래픽 임계치 초과, PC 오프라인 등 즉시 알림이 필요한 이벤트에 사용한다.
     * 프론트엔드는 {@code /topic/alerts}를 구독하여 팝업 또는 사운드 알림을 표시한다.
     *
     * @param alertData 알림 페이로드 (JSON 직렬화 가능 객체)
     */
    public void sendAlert(Object alertData) {
        messagingTemplate.convertAndSend("/topic/alerts", alertData);
    }
}