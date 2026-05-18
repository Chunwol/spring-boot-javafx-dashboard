package com.example.backend.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 포트로 들어오는 요청을 HTTPS 포트로 자동 리다이렉트하는 Tomcat 커넥터 설정.
 *
 * <p>활성화 조건 (모두 충족되어야 동작):
 * <ul>
 *   <li>{@code app.http-redirect.enabled=true} (기본값 {@code true})</li>
 *   <li>{@code server.ssl.key-store}가 설정되어 HTTPS 커넥터가 이미 기동되어 있을 것</li>
 *   <li>{@code app.http-redirect.port}와 {@code server.port}가 양수이며 서로 다를 것</li>
 * </ul>
 *
 * <p>WHY: Spring Security의 requiresChannel() 리다이렉트는 같은 포트에서의 스킴 전환에
 * 적합하다. 별도 HTTP 포트(예: 8888)에서 HTTPS 포트(443)로 포트 자체를 바꾸려면
 * Tomcat 레벨 추가 커넥터가 필요하다.
 *
 * <p>설정 키:
 * <pre>
 * app.http-redirect.enabled   (기본 true)
 * app.http-redirect.port      (기본 8888, HTTP 수신 포트)
 * server.port                 (기본 443, HTTPS 포트)
 * server.ssl.key-store        (SSL 인증서 경로; 빈 문자열이면 리다이렉트 비활성화)
 * </pre>
 */
@Configuration
public class HttpToHttpsRedirectConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpToHttpsRedirectConfig.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpToHttpsRedirectCustomizer(
            @Value("${app.http-redirect.enabled:true}") boolean enabled,
            @Value("${app.http-redirect.port:8888}") int httpPort,
            @Value("${server.port:443}") int httpsPort,
            @Value("${server.ssl.key-store:}") String keyStore
    ) {
        return factory -> {
            if (!enabled) {
                log.info("HTTP->HTTPS 리다이렉트 비활성화(app.http-redirect.enabled=false)");
                return;
            }
            if (keyStore == null || keyStore.isBlank()) {
                log.info("HTTP->HTTPS 리다이렉트 비활성화(server.ssl.key-store 미설정)");
                return;
            }
            if (httpPort <= 0 || httpsPort <= 0 || httpPort == httpsPort) {
                log.warn("HTTP->HTTPS 리다이렉트 비활성화(포트 설정 이상): httpPort={}, httpsPort={}", httpPort, httpsPort);
                return;
            }

            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(httpPort);
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalConnectors(connector);

            log.info("HTTP({}) -> HTTPS({}) 리다이렉트 커넥터 활성화", httpPort, httpsPort);
        };
    }
}
