package com.example.backend.service.notification;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 이메일 발송 서비스.
 *
 * <p>Spring {@link JavaMailSender}를 통해 단순 텍스트 메일을 발송한다.
 * SMTP 설정은 {@code spring.mail.*} 프로퍼티로 관리한다.
 *
 * <p>메일 발송 실패 시 예외를 상위로 전파하지 않고 에러 로그만 기록하여
 * 메일 장애가 핵심 비즈니스 로직에 영향을 주지 않도록 한다.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    /**
     * 단순 텍스트 이메일을 발송한다.
     *
     * <p>발송 실패 시 예외를 삼키고 에러 로그를 남긴다.
     * 메일 서버 장애가 호출자의 트랜잭션에 영향을 주지 않도록 하기 위함이다.
     *
     * @param to      수신자 이메일 주소
     * @param subject 메일 제목
     * @param body    메일 본문 (평문 텍스트)
     */
    public void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("메일 발송 실패 (to={}): {}", to, e.getMessage(), e);
        }
    }
}