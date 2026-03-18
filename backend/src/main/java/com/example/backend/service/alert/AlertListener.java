package com.example.backend.service.alert;

import com.example.backend.event.AlertEvent;
import com.example.backend.service.notification.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * AlertEvent 수신 → 이메일 발송.
 * 수신자: app.alert.email (application.yml 또는 환경변수 ALERT_EMAIL)
 * 미설정 시 이메일 발송 건너뜀.
 */
@Component
public class AlertListener implements ApplicationListener<AlertEvent> {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final MailService mailService;

    @Value("${app.alert.email:}")
    private String alertEmail;

    public AlertListener(MailService mailService) {
        this.mailService = mailService;
    }

    @Override
    @Async
    public void onApplicationEvent(AlertEvent event) {
        log.warn("[ALERT] MAC={} | {}", event.getMacAddress(), event.getMessage());

        if (alertEmail == null || alertEmail.isBlank()) {
            log.debug("ALERT_EMAIL 미설정 → 이메일 발송 건너뜀");
            return;
        }

        try {
            String subject = "[PC관제] 경고: " + event.getMacAddress();
            mailService.sendEmail(alertEmail, subject, event.getMessage());
        } catch (Exception e) {
            log.error("알림 이메일 발송 실패: {}", e.getMessage());
        }
    }
}
