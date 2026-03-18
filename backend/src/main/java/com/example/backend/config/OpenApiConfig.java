package com.example.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) 설정 클래스
 * API 문서의 제목, 설명, 버전 등을 정의합니다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        Contact contact = new Contact()
                .name("관리자")
                .email("admin@example.com")
                .url("https://example.com");

        License license = new License()
                .name("Apache 2.0")
                .url("https://www.apache.org/licenses/LICENSE-2.0.html");

        Info info = new Info()
                .title("강의실 통합 대시보드 API")
                .description("830대 PC 모니터링 및 원격 제어 시스템 API\n" +
                        "교사 PC와 학생 PC를 강의실별로 관리하고, 하트비트, 트래픽, 명령 큐 등을 제어합니다.")
                .version("1.0.0")
                .contact(contact)
                .license(license);

        Server devServer = new Server()
                .url("http://localhost:8080")
                .description("개발 서버");

        Server prodServer = new Server()
                .url("http://api.example.com")
                .description("프로덕션 서버");

        return new OpenAPI()
                .info(info)
                .servers(List.of(devServer, prodServer));
    }
}
