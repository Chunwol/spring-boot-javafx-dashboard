package com.example.backend.service.alert;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.alert.OperationalAlert;
import com.example.backend.service.notification.MailService;
import com.example.backend.service.realtime.WebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AlertEvent 수신 → 이메일 발송.
 * 수신자: app.alert.email (application.yml 또는 환경변수 ALERT_EMAIL)
 * 미설정 시 이메일 발송 건너뜀.
 */
@Component
public class AlertListener implements ApplicationListener<AlertEvent> {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final MailService mailService;
    private final AlertCenterService alertCenterService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;

    @Value("${app.alert.email:}")
    private String alertEmail;

    public AlertListener(MailService mailService,
                         AlertCenterService alertCenterService,
                         WebSocketService webSocketService,
                         ObjectMapper objectMapper) {
        this.mailService = mailService;
        this.alertCenterService = alertCenterService;
        this.webSocketService = webSocketService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Async
    public void onApplicationEvent(AlertEvent event) {
        log.warn("[ALERT] source={} severity={} type={} | {}",
                event.getSourceMac(), event.getSeverity(), event.getAlertType(), event.getMessage());

        OperationalAlert savedAlert = alertCenterService.create(event);
        sendRealtimeAlert(savedAlert);

        if (alertEmail == null || alertEmail.isBlank()) {
            log.debug("ALERT_EMAIL 미설정 → 이메일 발송 건너뜀");
            return;
        }

        try {
            String subject = "[PC관제] " + savedAlert.getSeverity() + ": " + savedAlert.getSourceMac();
            mailService.sendEmail(alertEmail, subject, savedAlert.getMessage());
        } catch (Exception e) {
            log.error("알림 이메일 발송 실패: {}", e.getMessage(), e);
        }
    }

    private void sendRealtimeAlert(OperationalAlert savedAlert) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", savedAlert.getId());
            payload.put("sourceMac", savedAlert.getSourceMac());
            payload.put("message", savedAlert.getMessage());
            payload.put("severity", savedAlert.getSeverity());
            payload.put("alertType", savedAlert.getAlertType());
            payload.put("acknowledged", savedAlert.getAcknowledged());
            payload.put("createdAt", savedAlert.getCreatedAt());
            webSocketService.sendAlert(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("실시간 알림 브로드캐스트 실패: {}", e.getMessage());
        }
    }
}
