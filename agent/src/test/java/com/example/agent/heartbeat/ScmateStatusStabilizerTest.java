package com.example.agent.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScmateStatusStabilizerTest {

    private static final long GRACE_NANOS = TimeUnit.MINUTES.toNanos(2);

    @Test
    void keepsLastStableStateDuringShortClientReconnect() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> active = status("ACTIVE", "PROTECTED", 0L);

        assertThat(stabilizer.stabilize(active, 0L)).isEqualTo(active);
        assertThat(stabilizer.stabilize(
                status("STATUS_UNAVAILABLE", "UNKNOWN", 1L), TimeUnit.SECONDS.toNanos(15)))
                .isEqualTo(active);
        assertThat(stabilizer.stabilize(
                status("ACTIVE", "PROTECTED", 0L), TimeUnit.SECONDS.toNanos(30)))
                .isEqualTo(active);
    }

    @Test
    void exposesTransientStateAfterGracePeriod() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> active = status("ACTIVE", "PROTECTED", 0L);
        Map<String, Object> disabled = status("PROTECT_DISABLED", "UNKNOWN", 9L);

        stabilizer.stabilize(active, 0L);
        assertThat(stabilizer.stabilize(disabled, TimeUnit.SECONDS.toNanos(15)))
                .isEqualTo(active);
        assertThat(stabilizer.stabilize(disabled, TimeUnit.SECONDS.toNanos(134)))
                .isEqualTo(active);
        assertThat(stabilizer.stabilize(disabled, TimeUnit.SECONDS.toNanos(135)))
                .isEqualTo(disabled);
    }

    @Test
    void resetsGraceWhenTransientReasonChanges() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> active = status("ACTIVE", "OPERATOR", 3L);

        stabilizer.stabilize(active, 0L);
        stabilizer.stabilize(status("STATUS_UNAVAILABLE", "UNKNOWN", 1L),
                TimeUnit.SECONDS.toNanos(15));
        assertThat(stabilizer.stabilize(status("PROTECT_DISABLED", "UNKNOWN", 9L),
                TimeUnit.SECONDS.toNanos(130))).isEqualTo(active);
    }

    @Test
    void acceptsConfirmedNotInstalledImmediately() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        stabilizer.stabilize(status("ACTIVE", "PROTECTED", 0L), 0L);

        Map<String, Object> notInstalled = status("NOT_INSTALLED", "UNKNOWN", 9L);

        assertThat(stabilizer.stabilize(notInstalled, TimeUnit.SECONDS.toNanos(15)))
                .isEqualTo(notInstalled);
    }

    @Test
    void requiresTwoConsecutiveActiveObservationsBeforeChangingMode() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> operator = status("ACTIVE", "OPERATOR", 3L);
        Map<String, Object> protectedMode = status("ACTIVE", "PROTECTED", 1L);

        stabilizer.stabilize(operator, 0L);

        assertThat(stabilizer.stabilize(protectedMode, TimeUnit.SECONDS.toNanos(15)))
                .isEqualTo(operator);
        assertThat(stabilizer.stabilize(protectedMode, TimeUnit.SECONDS.toNanos(30)))
                .isEqualTo(protectedMode);
    }

    @Test
    void activeObservationMustRemainConsecutiveToChangeMode() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> operator = status("ACTIVE", "OPERATOR", 3L);
        Map<String, Object> protectedMode = status("ACTIVE", "PROTECTED", 1L);

        stabilizer.stabilize(operator, 0L);
        stabilizer.stabilize(protectedMode, TimeUnit.SECONDS.toNanos(15));
        stabilizer.stabilize(operator, TimeUnit.SECONDS.toNanos(30));

        assertThat(stabilizer.stabilize(protectedMode, TimeUnit.SECONDS.toNanos(45)))
                .isEqualTo(operator);
    }

    @Test
    void confirmedModeChangeIsAcceptedImmediately() {
        ScmateStatusStabilizer stabilizer = new ScmateStatusStabilizer(GRACE_NANOS);
        Map<String, Object> operator = status("ACTIVE", "OPERATOR", 3L);
        Map<String, Object> protectedMode = status("ACTIVE", "PROTECTED", 1L);

        stabilizer.stabilize(operator, 0L);

        assertThat(stabilizer.recordConfirmed(protectedMode)).isEqualTo(protectedMode);
        assertThat(stabilizer.stabilize(operator, TimeUnit.SECONDS.toNanos(15)))
                .isEqualTo(protectedMode);
    }

    private static Map<String, Object> status(String reason, String mode, long raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scmateAvailable", "ACTIVE".equals(reason));
        value.put("scmateAvailabilityReason", reason);
        value.put("scmateMode", mode);
        value.put("scmateRawState", raw);
        return value;
    }
}
