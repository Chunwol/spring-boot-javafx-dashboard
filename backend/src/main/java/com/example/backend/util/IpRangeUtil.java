package com.example.backend.util;

/**
 * IPv4 주소 유효성 검사 및 범위 계산 유틸리티 클래스.
 *
 * <p>강의실 IP 범위 매핑({@code ClassroomIpRange})에서 PC가 특정 범위에
 * 속하는지 여부를 검사하는 데 주로 사용된다.</p>
 *
 * <p>이 클래스는 인스턴스화할 수 없는 정적 유틸리티 클래스이다.</p>
 */
public final class IpRangeUtil {

    private IpRangeUtil() {}

    /**
     * IPv4 주소 문자열을 32비트 long 값으로 변환한다.
     *
     * <p>예: {@code "192.168.1.1"} → {@code 3232235777L}</p>
     *
     * @param ip IPv4 주소 문자열 (null 불가)
     * @return 32비트 long 표현
     * @throws IllegalArgumentException ip가 null이거나 유효하지 않은 경우
     */
    public static long ipv4ToLong(String ip) {
        if (ip == null) throw new IllegalArgumentException("ip is null");
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("invalid ipv4: " + ip);
        long out = 0;
        for (int i = 0; i < 4; i++) {
            int p = Integer.parseInt(parts[i]);
            if (p < 0 || p > 255) throw new IllegalArgumentException("invalid ipv4: " + ip);
            out = (out << 8) | (p & 0xFF);
        }
        return out;
    }

    /**
     * 주어진 문자열이 유효한 IPv4 주소인지 검사한다.
     *
     * <p>점(.)으로 구분된 4개의 옥텟이 각각 0~255 범위인 경우에만 {@code true}를 반환한다.</p>
     *
     * @param ip 검사할 문자열 (null이면 false 반환)
     * @return 유효한 IPv4이면 {@code true}, 아니면 {@code false}
     */
    public static boolean isIpv4(String ip) {
        if (ip == null) return false;
        String trimmed = ip.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isBlank()) return false;
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * CIDR 표기에서 서브넷 브로드캐스트 IP를 계산한다.
     *
     * <p>예: {@code "192.168.1.0/24"} → {@code "192.168.1.255"}</p>
     *
     * @param cidr CIDR 표기 문자열 (예: {@code "10.0.0.0/8"})
     * @return 브로드캐스트 IP 문자열, 입력이 유효하지 않으면 {@code null}
     */
    public static String broadcastFromCidr(String cidr) {
        if (cidr == null || cidr.isBlank()) return null;
        String[] p = cidr.trim().split("/");
        if (p.length != 2 || !isIpv4(p[0])) return null;
        try {
            int prefix = Integer.parseInt(p[1].trim());
            if (prefix < 0 || prefix > 32) return null;
            long base = ipv4ToLong(p[0]);
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            long broadcast = (base & mask) | (~mask & 0xFFFFFFFFL);
            return String.format("%d.%d.%d.%d",
                    (broadcast >> 24) & 0xFF,
                    (broadcast >> 16) & 0xFF,
                    (broadcast >> 8) & 0xFF,
                    broadcast & 0xFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 주어진 IP 주소가 CIDR 범위 또는 시작/끝 IP 범위에 속하는지 확인한다.
     *
     * <p>CIDR 표기가 유효하면 CIDR 마스크로 판단하고,
     * 그렇지 않으면 시작 IP ~ 끝 IP 범위(포함)로 판단한다.</p>
     *
     * @param ip      검사할 IPv4 주소
     * @param cidr    CIDR 표기 (예: {@code "192.168.1.0/24"}), 없으면 {@code null}
     * @param startIp 시작 IP (CIDR 없을 때 사용), 없으면 {@code null}
     * @param endIp   끝 IP (CIDR 없을 때 사용), 없으면 {@code null}
     * @return 범위 내에 있으면 {@code true}, 아니면 {@code false}
     */
    public static boolean containsIp(String ip, String cidr, String startIp, String endIp) {
        if (!isIpv4(ip)) return false;
        long target = ipv4ToLong(ip);

        if (cidr != null && !cidr.isBlank()) {
            String[] p = cidr.trim().split("/");
            if (p.length == 2 && isIpv4(p[0])) {
                int prefix = Integer.parseInt(p[1]);
                if (prefix >= 0 && prefix <= 32) {
                    long base = ipv4ToLong(p[0]);
                    long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                    return (base & mask) == (target & mask);
                }
            }
        }

        if (startIp != null && endIp != null && isIpv4(startIp) && isIpv4(endIp)) {
            long s = ipv4ToLong(startIp);
            long e = ipv4ToLong(endIp);
            if (s > e) {
                long tmp = s; s = e; e = tmp;
            }
            return target >= s && target <= e;
        }

        return false;
    }
}
