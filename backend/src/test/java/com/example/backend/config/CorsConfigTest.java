package com.example.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CorsConfig.class);

    @Test
    void permitsLocalViteOriginsByDefault() {
        contextRunner.run(context -> {
            CorsConfiguration configuration = restCorsConfiguration(context.getBean(CorsConfigurationSource.class));

            assertThat(configuration.getAllowedOrigins()).containsExactly(
                    "http://localhost:5173",
                    "http://127.0.0.1:5173"
            );
            assertThat(configuration.getAllowCredentials()).isTrue();
            assertThat(configuration.checkOrigin("http://localhost:5173"))
                    .isEqualTo("http://localhost:5173");
            assertThat(configuration.checkOrigin("https://untrusted.example.com")).isNull();
        });
    }

    @Test
    void replacesDefaultsWithConfiguredExactOrigins() {
        contextRunner
                .withPropertyValues(
                        "app.security.cors.allowed-origins=https://dashboard.example.com,https://admin.example.com/"
                )
                .run(context -> {
                    CorsConfiguration configuration = restCorsConfiguration(
                            context.getBean(CorsConfigurationSource.class));

                    assertThat(configuration.getAllowedOrigins()).containsExactly(
                            "https://dashboard.example.com",
                            "https://admin.example.com"
                    );
                    assertThat(configuration.checkOrigin("http://localhost:5173")).isNull();
                    assertThat(configuration.checkOrigin("https://dashboard.example.com"))
                            .isEqualTo("https://dashboard.example.com");
                });
    }

    @Test
    void rejectsWildcardCredentialedOrigins() {
        contextRunner
                .withPropertyValues("app.security.cors.allowed-origins=*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .rootCause()
                            .hasMessageContaining("must not contain wildcards");
                });
    }

    @Test
    void doesNotApplyBrowserCorsPolicyOutsideRestApi() {
        contextRunner.run(context -> {
            CorsConfigurationSource source = context.getBean(CorsConfigurationSource.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/status");

            assertThat(source.getCorsConfiguration(request)).isNull();
        });
    }

    private CorsConfiguration restCorsConfiguration(CorsConfigurationSource source) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/dashboard");
        CorsConfiguration configuration = source.getCorsConfiguration(request);
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedMethods())
                .containsAll(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        assertThat(configuration.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "Accept", "Range");
        return configuration;
    }
}
