package com.example.backend.service.traffic;

import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficLogFlushServiceTest {

    @Test
    void readsLegacyLiveTrafficValueWithoutTimestamp() {
        TrafficLogFlushService service = serviceWithLiveValue("aa-bb-cc-dd-ee-01", "2048,512");

        Map<String, TrafficLogFlushService.LiveTraffic> snapshot = service.getLiveTrafficSnapshot();

        assertThat(snapshot).containsEntry("AABBCCDDEE01",
                new TrafficLogFlushService.LiveTraffic(2048L, 512L, null));
    }

    @Test
    void readsTimestampedLiveTrafficValue() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 25, 19, 30, 0);
        TrafficLogFlushService service = serviceWithLiveValue(
                "AA:BB:CC:DD:EE:02", "1024,256," + updatedAt);

        Map<String, TrafficLogFlushService.LiveTraffic> snapshot = service.getLiveTrafficSnapshot();

        assertThat(snapshot).containsEntry("AABBCCDDEE02",
                new TrafficLogFlushService.LiveTraffic(1024L, 256L, updatedAt));
    }

    @SuppressWarnings("unchecked")
    private TrafficLogFlushService serviceWithLiveValue(String macAddress, String value) {
        TrafficLogsRepository repository = mock(TrafficLogsRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("traffic:live:" + macAddress);
        when(valueOperations.multiGet(List.of("traffic:live:" + macAddress))).thenReturn(List.of(value));
        return new TrafficLogFlushService(repository, redis);
    }
}
