package com.example.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(MailFallbackConfig.class);

    /**
     * 메일 설정이 누락된 환경(테스트/개발)에서도 애플리케이션이 기동되도록 fallback sender를 제공한다.
     * 실제 발송 시에는 SMTP 설정이 필요하다.
     */
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender fallbackJavaMailSender() {
        log.warn("JavaMailSender bean not configured. Using fallback JavaMailSenderImpl.");
        return new JavaMailSenderImpl();
    }
}
