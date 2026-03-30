package com.example.backend.common.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class LenientLocalDateTimeStringConverterTest {

    private final LenientLocalDateTimeStringConverter converter = new LenientLocalDateTimeStringConverter();

    // ── convertToDatabaseColumn ─────────────────────────────────────────

    @Test
    void toDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void toDatabaseColumn_formatsCorrectly() {
        LocalDateTime dt = LocalDateTime.of(2026, 3, 30, 14, 30, 45);
        assertThat(converter.convertToDatabaseColumn(dt)).isEqualTo("2026-03-30 14:30:45");
    }

    // ── convertToEntityAttribute ────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void toEntityAttribute_nullOrBlank_returnsNull(String input) {
        assertThat(converter.convertToEntityAttribute(input)).isNull();
    }

    @Test
    void toEntityAttribute_spaceFormat() {
        LocalDateTime result = converter.convertToEntityAttribute("2026-03-30 14:30:45");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 30, 14, 30, 45));
    }

    @Test
    void toEntityAttribute_spaceFormatWithFraction() {
        LocalDateTime result = converter.convertToEntityAttribute("2026-03-30 14:30:45.123456789");
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getNano()).isEqualTo(123456789);
    }

    @Test
    void toEntityAttribute_isoFormat() {
        LocalDateTime result = converter.convertToEntityAttribute("2026-03-30T14:30:45");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 30, 14, 30, 45));
    }

    @Test
    void toEntityAttribute_isoFormatWithFraction() {
        LocalDateTime result = converter.convertToEntityAttribute("2026-03-30T14:30:45.500");
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getNano()).isEqualTo(500000000);
    }

    @Test
    void toEntityAttribute_epochSeconds() {
        // 1711800000 → 2024-03-30T12:00:00 UTC (varies by timezone)
        LocalDateTime result = converter.convertToEntityAttribute("1711800000");
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isGreaterThanOrEqualTo(2024);
    }

    @Test
    void toEntityAttribute_epochMillis() {
        // > 2_000_000_000 treated as millis
        LocalDateTime result = converter.convertToEntityAttribute("1711800000000");
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isGreaterThanOrEqualTo(2024);
    }

    @Test
    void toEntityAttribute_unparseable_returnsNull() {
        assertThat(converter.convertToEntityAttribute("not-a-date")).isNull();
    }

    @Test
    void roundTrip() {
        LocalDateTime original = LocalDateTime.of(2026, 1, 15, 10, 20, 30);
        String dbValue = converter.convertToDatabaseColumn(original);
        LocalDateTime restored = converter.convertToEntityAttribute(dbValue);
        assertThat(restored).isEqualTo(original);
    }
}
