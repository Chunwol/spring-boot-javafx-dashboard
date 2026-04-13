package com.example.backend.service.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /** PC 상태 변경 시 대시보드 갱신 알림 (frontend: /topic/dashboard 구독) */
    public void sendDashboardUpdate(Object data) {
        messagingTemplate.convertAndSend("/topic/dashboard", data);
    }

    /** 이상 감지 알림 (frontend: /topic/alerts 구독) */
    public void sendAlert(Object alertData) {
        messagingTemplate.convertAndSend("/topic/alerts", alertData);
    }
}