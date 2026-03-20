package com.example.backend.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * .env 파일에서 환경 변수를 로드하는 설정 클래스
 * Spring Boot 애플리케이션 시작 시 .env 파일의 모든 값을 시스템 속성으로 로드합니다.
 */
public class EnvConfig implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // .env 파일 경로 (프로젝트 루트)
        String envFilePath = ".env";
        File envFile = new File(envFilePath);

        // .env 파일이 존재하면 로드
        if (envFile.exists()) {
            Dotenv dotenv = Dotenv.configure()
                    .directory(".")
                    .filename(".env")
                    .ignoreIfMissing()
                    .load();

            // .env의 모든 값을 Map으로 변환
            Map<String, Object> envProperties = dotenv.entries()
                    .stream()
                    .collect(Collectors.toMap(
                            DotenvEntry::getKey,
                            DotenvEntry::getValue,
                            (existing, replacement) -> replacement
                    ));

            // Spring 환경에 MapPropertySource로 추가
            MapPropertySource envPropertySource = new MapPropertySource("dotenv", envProperties);
            environment.getPropertySources().addFirst(envPropertySource);
        }
    }
}
