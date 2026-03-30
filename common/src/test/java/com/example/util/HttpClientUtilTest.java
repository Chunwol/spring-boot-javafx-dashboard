package com.example.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class HttpClientUtilTest {

    @Test
    void parseJsonObject_validJson() throws Exception {
        String json = """
                {"name": "test", "value": 42, "active": true}
                """;
        Map<String, Object> result = HttpClientUtil.parseJsonObject(json);

        assertThat(result).containsEntry("name", "test");
        assertThat(result).containsEntry("value", 42);
        assertThat(result).containsEntry("active", true);
    }

    @Test
    void parseJsonObject_emptyObject() throws Exception {
        Map<String, Object> result = HttpClientUtil.parseJsonObject("{}");
        assertThat(result).isEmpty();
    }

    @Test
    void parseJsonObject_nestedObject() throws Exception {
        String json = """
                {"outer": {"inner": "value"}}
                """;
        Map<String, Object> result = HttpClientUtil.parseJsonObject(json);
        assertThat(result).containsKey("outer");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("outer");
        assertThat(inner).containsEntry("inner", "value");
    }

    @Test
    void parseJsonObject_invalidJson_throws() {
        assertThatThrownBy(() -> HttpClientUtil.parseJsonObject("not json"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void setBearerToken_doesNotThrow() {
        assertThatCode(() -> HttpClientUtil.setBearerToken("test-token"))
                .doesNotThrowAnyException();
        // Reset
        HttpClientUtil.setBearerToken(null);
    }
}
