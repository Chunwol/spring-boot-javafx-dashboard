package com.example.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) 설정 클래스.
 * Swagger UI 제목, 설명, 버전, JWT Bearer 보안 스키마를 정의한다.
 */
@Configuration
public class OpenApiConfig {

    /** JWT Bearer 보안 스키마 이름 */
    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

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
                .title("통합 강의실 관제 시스템 API")
                .description("강의실 PC 원격 관제 백엔드 REST API\n" +
                        "교사 PC와 학생 PC를 강의실별로 관리하고, 하트비트, 트래픽, 명령 큐 등을 제어합니다.\n\n" +
                        "**인증**: 대부분의 엔드포인트는 JWT Bearer 토큰을 요구합니다. " +
                        "`/api/v1/auth/login`으로 로그인 후 발급받은 토큰을 Authorization 헤더에 포함하세요.")
                .version("1.0.0")
                .contact(contact)
                .license(license);

        Server devServer = new Server()
                .url("http://localhost:8080")
                .description("개발 서버");

        Server prodServer = new Server()
                .url("http://api.example.com")
                .description("프로덕션 서버");

        // JWT Bearer 보안 스키마 정의
        SecurityScheme jwtSecurityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .name(SECURITY_SCHEME_NAME)
                .description("JWT 토큰을 입력하세요. 예: eyJhbGciOiJIUzI1NiJ9...");

        return new OpenAPI()
                .info(info)
                .servers(List.of(devServer, prodServer))
                // 전역 보안 요구사항 적용 (모든 엔드포인트에 Bearer 인증 표시)
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtSecurityScheme));
    }
}
