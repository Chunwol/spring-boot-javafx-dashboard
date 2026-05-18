package com.example.agent.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtaUpdaterTest {

    @Test
    void isNewer_handlesPrefixedSemanticVersions() {
        assertThat(OtaUpdater.isNewer("v1.2.4", "1.2.3")).isTrue();
        assertThat(OtaUpdater.isNewer("1.10.0", "1.9.9")).isTrue();
    }

    @Test
    void isNewer_returnsFalseForEqualOrOlder() {
        assertThat(OtaUpdater.isNewer("v1.2.3", "1.2.3")).isFalse();
        assertThat(OtaUpdater.isNewer("1.2.2", "1.2.3")).isFalse();
    }

    @Test
    void sanitizeVersion_defaultsToDevOnBlank() {
        assertThat(OtaUpdater.sanitizeVersion(null)).isEqualTo("dev");
        assertThat(OtaUpdater.sanitizeVersion("  ")).isEqualTo("dev");
        assertThat(OtaUpdater.sanitizeVersion(" v2.0.0 ")).isEqualTo("v2.0.0");
    }

    @Test
    void constructorOverride_setsCurrentVersion() {
        OtaUpdater updater = new OtaUpdater(
                new OkHttpClient(),
                new ObjectMapper(),
                "owner",
                "repo",
                "2.4.1"
        );

        assertThat(updater.currentVersion()).isEqualTo("2.4.1");
    }
}
