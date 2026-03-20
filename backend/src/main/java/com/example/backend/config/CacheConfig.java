package com.example.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${app.cache.ip-range.maximum-size:10000}")
    private long ipRangeCacheMaximumSize;

    @Value("${app.cache.ip-range.expire-minutes:10}")
    private long ipRangeCacheExpireMinutes;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("ipRangeCache");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(ipRangeCacheMaximumSize)
                .expireAfterWrite(ipRangeCacheExpireMinutes, TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }
}
