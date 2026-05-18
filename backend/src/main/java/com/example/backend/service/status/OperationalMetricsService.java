package com.example.backend.service.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 운영 메트릭 수집 및 조회 서비스.
 *
 * <p>수집 항목:
 * <ul>
 *   <li><b>명령 레이턴시</b>: dispatch(큐잉→전달), ack(전달→확인), result(확인→결과) 단계별</li>
 *   <li><b>파일 전송</b>: 업로드/다운로드 요청 수, 바이트, 실패 수</li>
 *   <li><b>HTTP 요청</b>: 역할별 요청 수, 403/4xx/5xx 비율</li>
 * </ul>
 *
 * <p><b>이중 저장 전략</b>: Micrometer {@link MeterRegistry}(글로벌 누적)와
 * 인메모리 시계열 Deque(최근 윈도우 슬라이딩)를 병행 사용한다.
 * Micrometer는 Prometheus/Actuator 연동용, Deque는 대시보드 실시간 뷰용이다.
 *
 * <p><b>스레드 안전성</b>: 시계열 Deque 접근은 {@code recentLock} 모니터로 동기화한다.
 * {@link java.util.concurrent.atomic.LongAdder}는 락 없이 원자적 증가를 제공한다.
 *
 * <p>DB를 조회하지 않으므로 트랜잭션이 불필요하다.
 */
@Service
public class OperationalMetricsService {

    private static final int DEFAULT_RECENT_WINDOW_SECONDS = 300;
    private static final int MAX_RECENT_WINDOW_SECONDS = 900;
    private static final long RECENT_HISTORY_MILLIS = TimeUnit.SECONDS.toMillis(MAX_RECENT_WINDOW_SECONDS);

    private final MeterRegistry meterRegistry;

    private final Timer commandDispatchLatencyTimer;
    private final Timer commandAckLatencyTimer;
    private final Timer commandResultLatencyTimer;
    private final Counter commandSuccessCounter;
    private final Counter commandFailureCounter;

    private final LongAdder uploadBytes = new LongAdder();
    private final LongAdder uploadDurationNanos = new LongAdder();
    private final LongAdder uploadRequests = new LongAdder();
    private final LongAdder uploadFailures = new LongAdder();

    private final LongAdder downloadBytes = new LongAdder();
    private final LongAdder downloadDurationNanos = new LongAdder();
    private final LongAdder downloadRequests = new LongAdder();
    private final LongAdder downloadFailures = new LongAdder();

    private final ConcurrentMap<String, LongAdder> transferFailureCodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RoleRequestStats> roleRequestStats = new ConcurrentHashMap<>();
    private final Object recentLock = new Object();
    private final Deque<TimedBooleanEvent> recentCommandResults = new ArrayDeque<>();
    private final Deque<TimedLongEvent> recentAckLatencies = new ArrayDeque<>();
    private final Deque<TimedLongEvent> recentResultLatencies = new ArrayDeque<>();
    private final Deque<TimedBooleanEvent> recentTransferResults = new ArrayDeque<>();
    private final Deque<TimedStatusEvent> recentRequestStatuses = new ArrayDeque<>();

    public OperationalMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.commandDispatchLatencyTimer = Timer.builder("ops.command.dispatch.latency")
                .description("Latency from command queued to delivered")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
        this.commandAckLatencyTimer = Timer.builder("ops.command.ack.latency")
                .description("Latency from delivered to ack")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
        this.commandResultLatencyTimer = Timer.builder("ops.command.result.latency")
                .description("Latency from ack to result")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
        this.commandSuccessCounter = Counter.builder("ops.command.result.success").register(meterRegistry);
        this.commandFailureCounter = Counter.builder("ops.command.result.failure").register(meterRegistry);
    }

    /**
     * 명령 dispatch 레이턴시(큐잉 → 에이전트 전달 완료)를 기록한다.
     *
     * @param latencyMs 레이턴시 (밀리초, 음수 무시)
     */
    public void recordCommandDispatchLatencyMillis(long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        commandDispatchLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 명령 ack 레이턴시(전달 → 에이전트 확인 응답)를 기록한다.
     * Micrometer 타이머와 슬라이딩 윈도우 Deque 양쪽에 저장한다.
     *
     * @param latencyMs 레이턴시 (밀리초, 음수 무시)
     */
    public void recordCommandAckLatencyMillis(long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        commandAckLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        long now = System.currentTimeMillis();
        synchronized (recentLock) {
            recentAckLatencies.addLast(new TimedLongEvent(now, latencyMs));
            pruneOldEvents(recentAckLatencies, now - RECENT_HISTORY_MILLIS);
        }
    }

    /**
     * 명령 결과 레이턴시(ack → 최종 결과 수신)와 성공/실패 카운터를 기록한다.
     *
     * @param latencyMs 레이턴시 (밀리초, 음수이면 타이머만 건너뜀)
     * @param success   명령 성공 여부
     */
    public void recordCommandResultLatencyMillis(long latencyMs, boolean success) {
        long now = System.currentTimeMillis();
        if (latencyMs >= 0) {
            commandResultLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
            synchronized (recentLock) {
                recentResultLatencies.addLast(new TimedLongEvent(now, latencyMs));
                pruneOldEvents(recentResultLatencies, now - RECENT_HISTORY_MILLIS);
            }
        }
        if (success) {
            commandSuccessCounter.increment();
        } else {
            commandFailureCounter.increment();
        }
        synchronized (recentLock) {
            recentCommandResults.addLast(new TimedBooleanEvent(now, success));
            pruneOldEvents(recentCommandResults, now - RECENT_HISTORY_MILLIS);
        }
    }

    /**
     * HTTP API 요청을 역할별로 집계한다.
     *
     * @param role         요청자 역할 (ADMIN/USER/VIEWER/AGENT/ANON; 불인식 값은 ANON으로 처리)
     * @param statusCode   HTTP 응답 상태 코드
     * @param durationNanos 요청 처리 시간 (나노초)
     */
    public void recordApiRequest(String role, int statusCode, long durationNanos) {
        long now = System.currentTimeMillis();
        String normalizedRole = normalizeRole(role);
        RoleRequestStats stats = roleRequestStats.computeIfAbsent(normalizedRole, key -> new RoleRequestStats());
        stats.total.increment();
        if (statusCode == 403) {
            stats.forbidden.increment();
        }
        if (statusCode >= 400) {
            stats.error.increment();
        }
        if (statusCode >= 500) {
            stats.serverError.increment();
        }
        if (durationNanos > 0) {
            stats.durationNanos.add(durationNanos);
        }

        meterRegistry.counter(
                "ops.http.request.total",
                "role", normalizedRole,
                "status", String.valueOf(statusCode)).increment();
        synchronized (recentLock) {
            recentRequestStatuses.addLast(new TimedStatusEvent(now, statusCode));
            pruneOldEvents(recentRequestStatuses, now - RECENT_HISTORY_MILLIS);
        }
    }

    /**
     * 파일 전송(업로드/다운로드) 메트릭을 기록한다.
     *
     * @param direction     전송 방향 ({@code "upload"} 또는 {@code "download"}, 대소문자 무시)
     * @param bytes         전송된 바이트 수
     * @param durationNanos 전송 소요 시간 (나노초)
     * @param statusCode    HTTP 응답 상태 코드 (200~399: 성공)
     * @param endpointGroup 엔드포인트 그룹 레이블 (Micrometer 태그용)
     */
    public void recordTransfer(String direction, long bytes, long durationNanos, int statusCode, String endpointGroup) {
        long now = System.currentTimeMillis();
        String normalizedDirection = "download".equalsIgnoreCase(direction) ? "download" : "upload";
        long safeBytes = Math.max(0L, bytes);
        long safeDuration = Math.max(0L, durationNanos);
        boolean success = statusCode >= 200 && statusCode < 400;

        if ("download".equals(normalizedDirection)) {
            downloadRequests.increment();
            downloadBytes.add(safeBytes);
            downloadDurationNanos.add(safeDuration);
            if (!success) {
                downloadFailures.increment();
            }
        } else {
            uploadRequests.increment();
            uploadBytes.add(safeBytes);
            uploadDurationNanos.add(safeDuration);
            if (!success) {
                uploadFailures.increment();
            }
        }

        if (!success) {
            transferFailureCodes.computeIfAbsent(String.valueOf(statusCode), key -> new LongAdder()).increment();
        }

        meterRegistry.counter(
                "ops.transfer.request.total",
                "direction", normalizedDirection,
                "endpoint", endpointGroup == null ? "unknown" : endpointGroup,
                "status", String.valueOf(statusCode))
                .increment();
        synchronized (recentLock) {
            recentTransferResults.addLast(new TimedBooleanEvent(now, success));
            pruneOldEvents(recentTransferResults, now - RECENT_HISTORY_MILLIS);
        }
    }

    /**
     * 누적 메트릭 전체 스냅샷을 반환한다.
     * Micrometer 타이머/카운터 기반이므로 서비스 재시작 시 초기화된다.
     *
     * @return latency/transfer/request 섹션을 포함한 메트릭 맵
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latency", buildLatencySnapshot());
        result.put("transfer", buildTransferSnapshot());
        result.put("request", buildRequestSnapshot());
        return result;
    }

    /**
     * 기본 윈도우({@value #DEFAULT_RECENT_WINDOW_SECONDS}초) 기준 최근 메트릭 스냅샷을 반환한다.
     *
     * @return 슬라이딩 윈도우 메트릭 맵
     */
    public Map<String, Object> snapshotRecent() {
        return snapshotRecent(DEFAULT_RECENT_WINDOW_SECONDS);
    }

    /**
     * 지정된 윈도우 기준 최근 메트릭 스냅샷을 반환한다.
     *
     * <p>인메모리 Deque에서 윈도우 이내 이벤트만 집계한다.
     * 오래된 이벤트는 최대 보존 기간({@value #MAX_RECENT_WINDOW_SECONDS}초) 초과 시 제거된다.
     *
     * @param windowSeconds 집계 윈도우 (초, 0 이하이면 기본값, 최대 {@value #MAX_RECENT_WINDOW_SECONDS}초)
     * @return latency/transfer/request 섹션을 포함한 슬라이딩 윈도우 메트릭 맵
     */
    public Map<String, Object> snapshotRecent(int windowSeconds) {
        int safeWindowSeconds = normalizeWindowSeconds(windowSeconds);
        long now = System.currentTimeMillis();
        long historyCutoff = now - RECENT_HISTORY_MILLIS;
        long windowCutoff = now - TimeUnit.SECONDS.toMillis(safeWindowSeconds);

        Map<String, Object> latency = new LinkedHashMap<>();
        Map<String, Object> transfer = new LinkedHashMap<>();
        Map<String, Object> request = new LinkedHashMap<>();

        synchronized (recentLock) {
            pruneOldEvents(recentCommandResults, historyCutoff);
            pruneOldEvents(recentAckLatencies, historyCutoff);
            pruneOldEvents(recentResultLatencies, historyCutoff);
            pruneOldEvents(recentTransferResults, historyCutoff);
            pruneOldEvents(recentRequestStatuses, historyCutoff);

            long commandSuccessCount = 0L;
            long commandFailureCount = 0L;
            for (TimedBooleanEvent event : recentCommandResults) {
                if (event.timestampMillis < windowCutoff) {
                    continue;
                }
                if (event.success) {
                    commandSuccessCount++;
                } else {
                    commandFailureCount++;
                }
            }
            long commandTotal = commandSuccessCount + commandFailureCount;
            List<Long> ackSamples = collectLatencySamples(recentAckLatencies, windowCutoff);
            List<Long> resultSamples = collectLatencySamples(recentResultLatencies, windowCutoff);
            latency.put("windowSeconds", safeWindowSeconds);
            latency.put("successCount", commandSuccessCount);
            latency.put("failureCount", commandFailureCount);
            latency.put("totalCount", commandTotal);
            latency.put("failureRate", ratio(commandFailureCount, commandTotal));
            latency.put("ackP95Ms", percentile(ackSamples, 0.95d));
            latency.put("resultP95Ms", percentile(resultSamples, 0.95d));
            latency.put("ackSampleCount", ackSamples.size());
            latency.put("resultSampleCount", resultSamples.size());

            long transferTotal = 0L;
            long transferFailureCount = 0L;
            for (TimedBooleanEvent event : recentTransferResults) {
                if (event.timestampMillis < windowCutoff) {
                    continue;
                }
                transferTotal++;
                if (!event.success) {
                    transferFailureCount++;
                }
            }
            transfer.put("windowSeconds", safeWindowSeconds);
            transfer.put("totalRequests", transferTotal);
            transfer.put("totalFailures", transferFailureCount);
            transfer.put("failureRate", ratio(transferFailureCount, transferTotal));

            long requestTotal = 0L;
            long forbiddenCount = 0L;
            long errorCount = 0L;
            long serverErrorCount = 0L;
            for (TimedStatusEvent event : recentRequestStatuses) {
                if (event.timestampMillis < windowCutoff) {
                    continue;
                }
                requestTotal++;
                if (event.statusCode == 403) {
                    forbiddenCount++;
                }
                if (event.statusCode >= 400) {
                    errorCount++;
                }
                if (event.statusCode >= 500) {
                    serverErrorCount++;
                }
            }
            request.put("windowSeconds", safeWindowSeconds);
            request.put("totalRequests", requestTotal);
            request.put("forbidden", forbiddenCount);
            request.put("errors", errorCount);
            request.put("serverErrors", serverErrorCount);
            request.put("forbiddenRate", ratio(forbiddenCount, requestTotal));
            request.put("errorRate", ratio(errorCount, requestTotal));
            request.put("serverErrorRate", ratio(serverErrorCount, requestTotal));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowSeconds", safeWindowSeconds);
        result.put("latency", latency);
        result.put("transfer", transfer);
        result.put("request", request);
        return result;
    }

    private Map<String, Object> buildLatencySnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchAvgMs", nanosToMillis(commandDispatchLatencyTimer.mean(TimeUnit.NANOSECONDS)));
        out.put("dispatchP95Ms", timerPercentileMs(commandDispatchLatencyTimer, 0.95));
        out.put("ackAvgMs", nanosToMillis(commandAckLatencyTimer.mean(TimeUnit.NANOSECONDS)));
        out.put("ackP95Ms", timerPercentileMs(commandAckLatencyTimer, 0.95));
        out.put("resultAvgMs", nanosToMillis(commandResultLatencyTimer.mean(TimeUnit.NANOSECONDS)));
        out.put("resultP95Ms", timerPercentileMs(commandResultLatencyTimer, 0.95));

        double successCount = commandSuccessCounter.count();
        double failureCount = commandFailureCounter.count();
        double total = successCount + failureCount;
        out.put("successCount", (long) successCount);
        out.put("failureCount", (long) failureCount);
        out.put("failureRate", total <= 0.0 ? 0.0 : failureCount / total);
        return out;
    }

    private Map<String, Object> buildTransferSnapshot() {
        long upReq = uploadRequests.sum();
        long upBytes = uploadBytes.sum();
        long upDuration = uploadDurationNanos.sum();
        long upFail = uploadFailures.sum();

        long downReq = downloadRequests.sum();
        long downBytes = downloadBytes.sum();
        long downDuration = downloadDurationNanos.sum();
        long downFail = downloadFailures.sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadRequests", upReq);
        out.put("uploadBytes", upBytes);
        out.put("uploadFailures", upFail);
        out.put("uploadAvgMbps", throughputMbps(upBytes, upDuration));
        out.put("uploadAvgDurationMs", averageDurationMs(upDuration, upReq));

        out.put("downloadRequests", downReq);
        out.put("downloadBytes", downBytes);
        out.put("downloadFailures", downFail);
        out.put("downloadAvgMbps", throughputMbps(downBytes, downDuration));
        out.put("downloadAvgDurationMs", averageDurationMs(downDuration, downReq));

        long totalReq = upReq + downReq;
        long totalFail = upFail + downFail;
        out.put("totalRequests", totalReq);
        out.put("totalFailures", totalFail);
        out.put("failureRate", totalReq == 0 ? 0.0 : (double) totalFail / (double) totalReq);
        out.put("failureCodes", buildFailureCodesSnapshot());
        return out;
    }

    private Map<String, Object> buildRequestSnapshot() {
        long total = 0L;
        long forbidden = 0L;
        long error = 0L;
        long serverError = 0L;
        long duration = 0L;

        Map<String, Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String, RoleRequestStats> entry : roleRequestStats.entrySet()) {
            RoleRequestStats stats = entry.getValue();
            long roleTotal = stats.total.sum();
            long roleForbidden = stats.forbidden.sum();
            long roleError = stats.error.sum();
            long roleServerError = stats.serverError.sum();
            long roleDuration = stats.durationNanos.sum();

            total += roleTotal;
            forbidden += roleForbidden;
            error += roleError;
            serverError += roleServerError;
            duration += roleDuration;

            Map<String, Object> roleRow = new LinkedHashMap<>();
            roleRow.put("requests", roleTotal);
            roleRow.put("forbidden", roleForbidden);
            roleRow.put("errors", roleError);
            roleRow.put("serverErrors", roleServerError);
            roleRow.put("forbiddenRate", ratio(roleForbidden, roleTotal));
            roleRow.put("errorRate", ratio(roleError, roleTotal));
            roleRow.put("avgLatencyMs", averageDurationMs(roleDuration, roleTotal));
            roles.put(entry.getKey(), roleRow);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roles", roles);
        out.put("totalRequests", total);
        out.put("forbidden", forbidden);
        out.put("errors", error);
        out.put("serverErrors", serverError);
        out.put("forbiddenRate", ratio(forbidden, total));
        out.put("errorRate", ratio(error, total));
        out.put("serverErrorRate", ratio(serverError, total));
        out.put("avgLatencyMs", averageDurationMs(duration, total));
        return out;
    }

    private Map<String, Object> buildFailureCodesSnapshot() {
        Map<String, Object> failureCodes = new LinkedHashMap<>();
        transferFailureCodes.forEach((code, count) -> failureCodes.put(code, count.sum()));
        return failureCodes;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "ANON";
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ADMIN", "USER", "VIEWER", "AGENT", "ANON" -> normalized;
            default -> "ANON";
        };
    }

    private int normalizeWindowSeconds(int windowSeconds) {
        if (windowSeconds <= 0) {
            return DEFAULT_RECENT_WINDOW_SECONDS;
        }
        return Math.min(windowSeconds, MAX_RECENT_WINDOW_SECONDS);
    }

    private List<Long> collectLatencySamples(Deque<TimedLongEvent> source, long cutoffTimestampMillis) {
        List<Long> out = new ArrayList<>();
        for (TimedLongEvent event : source) {
            if (event.timestampMillis >= cutoffTimestampMillis) {
                out.add(event.value);
            }
        }
        return out;
    }

    private double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        values.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index);
    }

    private <T extends TimedEvent> void pruneOldEvents(Deque<T> source, long cutoffTimestampMillis) {
        while (!source.isEmpty()) {
            T head = source.peekFirst();
            if (head == null || head.timestampMillis() >= cutoffTimestampMillis) {
                return;
            }
            source.pollFirst();
        }
    }

    private double timerPercentileMs(Timer timer, double percentile) {
        ValueAtPercentile[] values = timer.takeSnapshot().percentileValues();
        for (ValueAtPercentile value : values) {
            if (Math.abs(value.percentile() - percentile) < 0.0001d) {
                return nanosToMillis(value.value(TimeUnit.NANOSECONDS));
            }
        }
        return 0.0d;
    }

    private double throughputMbps(long bytes, long durationNanos) {
        if (bytes <= 0 || durationNanos <= 0) {
            return 0.0d;
        }
        double seconds = durationNanos / 1_000_000_000.0d;
        if (seconds <= 0.0d) {
            return 0.0d;
        }
        return (bytes * 8.0d) / seconds / 1_000_000.0d;
    }

    private double averageDurationMs(long durationNanos, long count) {
        if (count <= 0L || durationNanos <= 0L) {
            return 0.0d;
        }
        return (durationNanos / (double) count) / 1_000_000.0d;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private double nanosToMillis(double nanos) {
        if (Double.isNaN(nanos) || Double.isInfinite(nanos) || nanos <= 0.0d) {
            return 0.0d;
        }
        return nanos / 1_000_000.0d;
    }

    private static final class RoleRequestStats {
        private final LongAdder total = new LongAdder();
        private final LongAdder forbidden = new LongAdder();
        private final LongAdder error = new LongAdder();
        private final LongAdder serverError = new LongAdder();
        private final LongAdder durationNanos = new LongAdder();
    }

    private interface TimedEvent {
        long timestampMillis();
    }

    private static final class TimedBooleanEvent implements TimedEvent {
        private final long timestampMillis;
        private final boolean success;

        private TimedBooleanEvent(long timestampMillis, boolean success) {
            this.timestampMillis = timestampMillis;
            this.success = success;
        }

        @Override
        public long timestampMillis() {
            return timestampMillis;
        }
    }

    private static final class TimedLongEvent implements TimedEvent {
        private final long timestampMillis;
        private final long value;

        private TimedLongEvent(long timestampMillis, long value) {
            this.timestampMillis = timestampMillis;
            this.value = value;
        }

        @Override
        public long timestampMillis() {
            return timestampMillis;
        }
    }

    private static final class TimedStatusEvent implements TimedEvent {
        private final long timestampMillis;
        private final int statusCode;

        private TimedStatusEvent(long timestampMillis, int statusCode) {
            this.timestampMillis = timestampMillis;
            this.statusCode = statusCode;
        }

        @Override
        public long timestampMillis() {
            return timestampMillis;
        }
    }
}
