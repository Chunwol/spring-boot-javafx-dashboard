package com.example.agent.heartbeat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ScmateStatusStabilizer {

    private final long transientGraceNanos;
    private Map<String, Object> lastStableStatus = Map.of();
    private String pendingTransientReason;
    private long pendingTransientSinceNanos;
    private String pendingActiveMode;
    private int pendingActiveModeCount;

    ScmateStatusStabilizer(long transientGraceNanos) {
        if (transientGraceNanos < 0) {
            throw new IllegalArgumentException("transientGraceNanos must not be negative");
        }
        this.transientGraceNanos = transientGraceNanos;
    }

    synchronized Map<String, Object> stabilize(Map<String, Object> observedStatus, long nowNanos) {
        Map<String, Object> observed = immutableCopy(observedStatus);
        String reason = String.valueOf(observed.getOrDefault(
                "scmateAvailabilityReason", "STATUS_UNAVAILABLE"));
        if (isStableReason(reason)) {
            if (isConflictingActiveMode(observed, reason)) {
                String observedMode = modeOf(observed);
                if (observedMode.equals(pendingActiveMode)) {
                    pendingActiveModeCount++;
                } else {
                    pendingActiveMode = observedMode;
                    pendingActiveModeCount = 1;
                }
                clearPendingTransient();
                if (pendingActiveModeCount < 2) {
                    return lastStableStatus;
                }
            }
            lastStableStatus = observed;
            clearPendingActiveMode();
            clearPendingTransient();
            return observed;
        }
        clearPendingActiveMode();
        if (lastStableStatus.isEmpty()) {
            return observed;
        }

        if (!reason.equals(pendingTransientReason)) {
            pendingTransientReason = reason;
            pendingTransientSinceNanos = nowNanos;
            return lastStableStatus;
        }
        long elapsedNanos = Math.max(0L, nowNanos - pendingTransientSinceNanos);
        if (elapsedNanos < transientGraceNanos) {
            return lastStableStatus;
        }

        lastStableStatus = Map.of();
        clearPendingTransient();
        return observed;
    }

    synchronized Map<String, Object> recordConfirmed(Map<String, Object> confirmedStatus) {
        Map<String, Object> confirmed = immutableCopy(confirmedStatus);
        lastStableStatus = confirmed;
        clearPendingActiveMode();
        clearPendingTransient();
        return confirmed;
    }

    private boolean isConflictingActiveMode(Map<String, Object> observed, String observedReason) {
        if (!"ACTIVE".equalsIgnoreCase(observedReason) || lastStableStatus.isEmpty()) {
            return false;
        }
        String stableReason = String.valueOf(lastStableStatus.getOrDefault(
                "scmateAvailabilityReason", "STATUS_UNAVAILABLE"));
        return "ACTIVE".equalsIgnoreCase(stableReason)
                && !modeOf(observed).equals(modeOf(lastStableStatus));
    }

    private static String modeOf(Map<String, Object> status) {
        return String.valueOf(status.getOrDefault("scmateMode", "UNKNOWN")).toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isStableReason(String reason) {
        return "ACTIVE".equalsIgnoreCase(reason) || "NOT_INSTALLED".equalsIgnoreCase(reason);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> status) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(status));
    }

    private void clearPendingTransient() {
        pendingTransientReason = null;
        pendingTransientSinceNanos = 0L;
    }

    private void clearPendingActiveMode() {
        pendingActiveMode = null;
        pendingActiveModeCount = 0;
    }
}
