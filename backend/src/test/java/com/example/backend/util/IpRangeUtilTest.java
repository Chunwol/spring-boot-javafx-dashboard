package com.example.backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class IpRangeUtilTest {

    // ── ipv4ToLong ──────────────────────────────────────────────────────

    @Test
    void ipv4ToLong_validIp() {
        assertThat(IpRangeUtil.ipv4ToLong("0.0.0.0")).isEqualTo(0L);
        assertThat(IpRangeUtil.ipv4ToLong("255.255.255.255")).isEqualTo(4294967295L);
        assertThat(IpRangeUtil.ipv4ToLong("192.168.1.100")).isEqualTo(3232235876L);
        assertThat(IpRangeUtil.ipv4ToLong("10.0.0.1")).isEqualTo(167772161L);
    }

    @Test
    void ipv4ToLong_nullThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> IpRangeUtil.ipv4ToLong(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "192.168.1", "192.168.1.1.1", "999.0.0.1"})
    void ipv4ToLong_invalidThrows(String ip) {
        assertThatThrownBy(() -> IpRangeUtil.ipv4ToLong(ip))
                .isInstanceOf(RuntimeException.class);
    }

    // ── isIpv4 ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "'192.168.1.1', true",
            "'0.0.0.0', true",
            "'255.255.255.255', true",
            "'10.0.0.1', true",
            "'256.0.0.1', false",
            "'192.168.1', false",
            "'abc.def.ghi.jkl', false",
            "' ', false"
    })
    void isIpv4(String ip, boolean expected) {
        assertThat(IpRangeUtil.isIpv4(ip)).isEqualTo(expected);
    }

    @Test
    void isIpv4_null_returnsFalse() {
        assertThat(IpRangeUtil.isIpv4(null)).isFalse();
    }

    // ── containsIp (CIDR) ───────────────────────────────────────────────

    @Test
    void containsIp_cidr_match() {
        assertThat(IpRangeUtil.containsIp("192.168.1.100", "192.168.1.0/24", null, null)).isTrue();
        assertThat(IpRangeUtil.containsIp("192.168.1.0", "192.168.1.0/24", null, null)).isTrue();
        assertThat(IpRangeUtil.containsIp("192.168.1.255", "192.168.1.0/24", null, null)).isTrue();
    }

    @Test
    void containsIp_cidr_noMatch() {
        assertThat(IpRangeUtil.containsIp("192.168.2.1", "192.168.1.0/24", null, null)).isFalse();
        assertThat(IpRangeUtil.containsIp("10.0.0.1", "192.168.1.0/24", null, null)).isFalse();
    }

    @Test
    void containsIp_cidr32_exactMatch() {
        assertThat(IpRangeUtil.containsIp("192.168.1.1", "192.168.1.1/32", null, null)).isTrue();
        assertThat(IpRangeUtil.containsIp("192.168.1.2", "192.168.1.1/32", null, null)).isFalse();
    }

    // ── containsIp (range) ─────────────────────────────────────────────

    @Test
    void containsIp_range_match() {
        assertThat(IpRangeUtil.containsIp("192.168.1.50", null, "192.168.1.1", "192.168.1.100")).isTrue();
        assertThat(IpRangeUtil.containsIp("192.168.1.1", null, "192.168.1.1", "192.168.1.100")).isTrue();
        assertThat(IpRangeUtil.containsIp("192.168.1.100", null, "192.168.1.1", "192.168.1.100")).isTrue();
    }

    @Test
    void containsIp_range_noMatch() {
        assertThat(IpRangeUtil.containsIp("192.168.1.101", null, "192.168.1.1", "192.168.1.100")).isFalse();
        assertThat(IpRangeUtil.containsIp("192.168.0.255", null, "192.168.1.1", "192.168.1.100")).isFalse();
    }

    @Test
    void containsIp_range_swappedStartEnd() {
        // start > end should still work (auto-swap)
        assertThat(IpRangeUtil.containsIp("192.168.1.50", null, "192.168.1.100", "192.168.1.1")).isTrue();
    }

    @Test
    void containsIp_invalidTarget_returnsFalse() {
        assertThat(IpRangeUtil.containsIp("invalid", "192.168.1.0/24", null, null)).isFalse();
        assertThat(IpRangeUtil.containsIp(null, "192.168.1.0/24", null, null)).isFalse();
    }

    @Test
    void containsIp_noCidrNoRange_returnsFalse() {
        assertThat(IpRangeUtil.containsIp("192.168.1.1", null, null, null)).isFalse();
        assertThat(IpRangeUtil.containsIp("192.168.1.1", "", null, null)).isFalse();
    }
}
