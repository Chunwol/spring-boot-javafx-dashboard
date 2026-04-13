package com.example.backend.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
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

    private static final Log log = new DeferredLog();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // backend/.env 파일 경로 우선 시도
        String[] searchPaths = {
            "backend/.env",    // 프로젝트 루트에서 backend 폴더
            "backend.env",      // 프로젝트 루트에 backend.env 파일
            ".env"              // 호환성: 기본 .env 파일
        };
        
        Dotenv dotenv = null;
        File foundFile = null;
        
        for (String path : searchPaths) {
            File file = new File(path);
            if (file.exists()) {
                foundFile = file;
                String dir = file.getParent() != null ? file.getParent() : ".";
                String filename = file.getName();
                dotenv = Dotenv.configure()
                        .directory(dir)
                        .filename(filename)
                        .ignoreIfMissing()
                        .load();
                log.info("Loaded backend env from: " + file.getAbsolutePath());
                break;
            }
        }

        if (dotenv != null) {
            // .env의 모든 값을 Map으로 변환
            Map<String, Object> envProperties = dotenv.entries()
                    .stream()
                    .collect(Collectors.toMap(
                            DotenvEntry::getKey,
                            DotenvEntry::getValue,
                            (existing, replacement) -> replacement
                    ));

            // Spring 환경에 MapPropertySource로 추가
            MapPropertySource envPropertySource = new MapPropertySource("backend-dotenv", envProperties);
            environment.getPropertySources().addFirst(envPropertySource);
        } else {
            log.warn("No backend env file found. Using defaults.");
        }
    }
}
