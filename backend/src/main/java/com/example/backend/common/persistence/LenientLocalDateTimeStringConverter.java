package com.example.backend.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * SQLite timestamp 문자열이 섞여 있을 때도 안전하게 LocalDateTime으로 변환한다.
 * - yyyy-MM-dd HH:mm:ss[.fraction]
 * - yyyy-MM-dd'T'HH:mm:ss[.fraction]
 * - epoch seconds / epoch millis 숫자 문자열
 */
@Converter
public class LenientLocalDateTimeStringConverter implements AttributeConverter<LocalDateTime, String> {

    private static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FLEX_SPACE = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();
    private static final DateTimeFormatter FLEX_ISO = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    @Override
    public String convertToDatabaseColumn(LocalDateTime attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.format(DB_FORMAT);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        String text = dbData.trim();
        try {
            if (text.matches("^\\d+$")) {
                long raw = Long.parseLong(text);
                long epochSec = raw > 2_000_000_000L ? raw / 1000L : raw;
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault());
            }
            try {
                return LocalDateTime.parse(text, FLEX_SPACE);
            } catch (Exception ignored) {
                return LocalDateTime.parse(text, FLEX_ISO);
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
