package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 애플리케이션 진입점.
 *
 * <p>어노테이션 역할:
 * <ul>
 *   <li>{@code @SpringBootApplication}: {@code com.example.backend} 패키지와 하위 패키지를
 *       모두 컴포넌트 스캔 대상으로 등록한다. AutoConfiguration, Configuration, Bean 스캔을 통합.</li>
 *   <li>{@code @EnableScheduling}: {@code @Scheduled} 어노테이션이 붙은 메서드를 주기 실행한다.
 *       실제 스레드 풀은 {@link com.example.backend.config.AsyncSchedulingConfig}에서 설정한다.</li>
 *   <li>{@code @EnableAsync}: {@code @Async} 어노테이션이 붙은 메서드를 비동기로 실행한다.
 *       실제 실행자는 {@code AsyncSchedulingConfig.applicationTaskExecutor()}를 사용한다.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}