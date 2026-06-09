package com.example.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 캐시 설정 클래스.
 * IP 범위 캐시(ipRangeCache)에 대해 Caffeine(로컬) 또는 Redis(분산) 중 하나를 선택적으로 활성화한다.
 *
 * <ul>
 *   <li>{@code app.cache.provider=caffeine} → Caffeine 인메모리 캐시 활성화 (단일 인스턴스)</li>
 *   <li>{@code app.cache.provider=redis} (기본값) → Redis 분산 캐시 활성화 (다중 인스턴스)</li>
 * </ul>
 *
 * <p>캐시 설정 프로퍼티:</p>
 * <ul>
 *   <li>{@code app.cache.ip-range.maximum-size} (기본: 10000) — Caffeine 최대 항목 수</li>
 *   <li>{@code app.cache.ip-range.expire-minutes} (기본: 10) — 캐시 TTL (분)</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Caffeine 캐시 최대 항목 수 (기본값: 10000개) */
    @Value("${app.cache.ip-range.maximum-size:10000}")
    private long ipRangeCacheMaximumSize;

    /** 캐시 만료 시간 (분, 기본값: 10분) */
    @Value("${app.cache.ip-range.expire-minutes:10}")
    private long ipRangeCacheExpireMinutes;

    /**
     * Caffeine 로컬 캐시 매니저.
     * {@code app.cache.provider=caffeine}일 때만 활성화된다.
     * 단일 서버 환경에 적합하며, 통계(recordStats) 수집을 지원한다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.cache.provider", havingValue = "caffeine")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("ipRangeCache", "pcRoleCache");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(ipRangeCacheMaximumSize)          // 최대 10,000개 항목
                .expireAfterWrite(ipRangeCacheExpireMinutes, TimeUnit.MINUTES) // TTL 후 만료
                .recordStats());                               // 캐시 통계 수집 활성화
        return manager;
    }

    /**
     * Redis 분산 캐시 매니저.
     * {@code app.cache.provider=redis}(기본값)일 때 활성화된다.
     * null 값은 캐싱하지 않으며, 키는 문자열 직렬화를 사용한다.
     * 트랜잭션 인식(transactionAware) 모드로 설정된다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.cache.provider", havingValue = "redis", matchIfMissing = true)
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        // TTL은 최소 1분 보장
        Duration ttl = Duration.ofMinutes(Math.max(1, ipRangeCacheExpireMinutes));

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)                                  // TTL 설정
                .disableCachingNullValues()                     // null 값 캐싱 금지
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer())); // 키 문자열 직렬화

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(config)
                .withInitialCacheConfigurations(Map.of(
                        "ipRangeCache", config,
                        "pcRoleCache", config)) // classroom resolution caches 초기 구성
                .transactionAware()                             // 트랜잭션 커밋 후 캐시 반영
                .build();
    }
}
