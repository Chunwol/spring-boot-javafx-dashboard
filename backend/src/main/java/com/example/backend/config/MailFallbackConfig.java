package com.example.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 메일 발송 관련 Fallback 빈 설정.
 *
 * <p>SMTP 설정({@code spring.mail.*})이 없는 개발·테스트 환경에서도
 * 애플리케이션이 정상 기동되도록 더미 {@link JavaMailSender}를 제공한다.
 * 실제 메일 발송이 필요한 경우 {@code spring.mail.host} 등 SMTP 설정을 추가하면
 * Spring Boot AutoConfiguration이 우선 등록하며 이 Fallback은 자동으로 비활성화된다.
 */
@Configuration
public class MailFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(MailFallbackConfig.class);

    /**
     * {@link JavaMailSender} 빈이 등록되어 있지 않을 때만 생성되는 Fallback 구현체.
     * 실제 SMTP 연결 없이 객체만 생성하므로 발송 시도 시 예외가 발생한다.
     * 운영 환경에서는 반드시 SMTP 설정을 통해 실제 구현체를 등록해야 한다.
     */
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender fallbackJavaMailSender() {
        log.warn("JavaMailSender bean not configured. Using fallback JavaMailSenderImpl.");
        return new JavaMailSenderImpl();
    }
}
