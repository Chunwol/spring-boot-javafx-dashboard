package com.example.backend.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * DB의 다양한 타임스탬프 문자열 형식을 {@link LocalDateTime}으로 변환하는 JPA AttributeConverter.
 *
 * <p>지원하는 입력 형식:
 * <ul>
 *   <li>{@code yyyy-MM-dd HH:mm:ss[.fraction]} — SQLite 기본 datetime 형식</li>
 *   <li>{@code yyyy-MM-dd'T'HH:mm:ss[.fraction]} — ISO-8601 형식</li>
 *   <li>순수 숫자 문자열 — epoch seconds 또는 epoch milliseconds 자동 판별
 *       (값이 2,000,000,000 초과이면 밀리초로 판단하여 1000으로 나눔)</li>
 * </ul>
 *
 * <p>DB 저장 형식: {@code yyyy-MM-dd HH:mm:ss} (초 단위, 고정)
 *
 * <p>WHY: SQLite는 네이티브 날짜 타입이 없어 텍스트로 저장한다.
 * 레거시 데이터가 여러 형식으로 혼재되어 있어 허용 범위를 넓게 설정했다.
 * 파싱 실패 시 null을 반환하고 TRACE 로그만 남겨 운영을 방해하지 않는다.
 */
@Converter
public class LenientLocalDateTimeStringConverter implements AttributeConverter<LocalDateTime, String> {

    private static final Logger log = LoggerFactory.getLogger(LenientLocalDateTimeStringConverter.class);

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
            } catch (Exception e) {
                return LocalDateTime.parse(text, FLEX_ISO);
            }
        } catch (Exception e) {
            log.trace("LocalDateTime 파싱 실패: {}", text);
            return null;
        }
    }
}
