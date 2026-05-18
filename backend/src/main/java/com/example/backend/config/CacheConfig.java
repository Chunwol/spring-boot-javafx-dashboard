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

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${app.cache.ip-range.maximum-size:10000}")
    private long ipRangeCacheMaximumSize;

    @Value("${app.cache.ip-range.expire-minutes:10}")
    private long ipRangeCacheExpireMinutes;

    @Bean
    @ConditionalOnProperty(name = "app.cache.provider", havingValue = "caffeine")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("ipRangeCache");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(ipRangeCacheMaximumSize)
                .expireAfterWrite(ipRangeCacheExpireMinutes, TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.provider", havingValue = "redis", matchIfMissing = true)
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        Duration ttl = Duration.ofMinutes(Math.max(1, ipRangeCacheExpireMinutes));
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(config)
                .withInitialCacheConfigurations(Map.of("ipRangeCache", config))
                .transactionAware()
                .build();
    }
}
