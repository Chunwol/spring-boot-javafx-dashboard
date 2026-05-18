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
 * HTTPS가 활성화된 경우 HTTP 포트로 들어온 요청을 HTTPS 포트로 리다이렉트한다.
 *
 * 전제:
 * - server.ssl.key-store 가 설정되어 HTTPS 커넥터가 떠 있어야 한다.
 * - app.http-redirect.enabled=true 일 때만 동작한다.
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
