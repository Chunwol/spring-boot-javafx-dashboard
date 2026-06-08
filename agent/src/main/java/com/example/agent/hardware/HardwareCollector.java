package com.example.agent.hardware;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Enumeration;

/**
 * OSHI 기반 하드웨어/네트워크 정보 수집기.
 * 델타 트래픽 측정을 위해 상태를 보관한다.
 */
public class HardwareCollector {

    private static final Logger log = LoggerFactory.getLogger(HardwareCollector.class);

    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hal = systemInfo.getHardware();
    private final OperatingSystem os = systemInfo.getOperatingSystem();

    // MEDIUM FIX: Synchronize access to network measurement state
    private final Object networkLock = new Object();
    private long lastBytesRecv = -1;
    private long lastBytesSent = -1;
    private long lastMeasureTime = -1;

    /** 백엔드 서버 호스트/포트 (라우팅 기반 IP 탐지에 사용) */
    private final String serverHost;
    private final int serverPort;

    public HardwareCollector(String serverUrl) {
        String host = null;
        int port = 8888;
        if (serverUrl != null && !serverUrl.isBlank()) {
            try {
                URI uri = URI.create(serverUrl.trim());
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort()
                        : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            } catch (Exception e) {
                log.debug("serverUrl 파싱 실패: {}", e.getMessage());
            }
        }
        this.serverHost = host;
        this.serverPort = port;
    }

    public HardwareCollector() {
        this(null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 라우팅 기반 IP/NIC 탐지
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 백엔드 서버로 향하는 실제 로컬 IP.
     * UDP DatagramSocket.connect()를 이용해 OS 라우팅 테이블만 조회 — 실제 패킷 미발송.
     * VMware/VPN/복수 NIC 환경에서 항상 올바른 IP를 반환한다.
     */
    private String getRoutingLocalIp() {
        if (serverHost == null || serverHost.isBlank()) return null;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(serverHost), serverPort);
            String ip = socket.getLocalAddress().getHostAddress();
            if (ip == null || ip.isBlank() || ip.equals("0.0.0.0") || ip.startsWith("127.")) return null;
            log.debug("라우팅 IP 조회: {} (서버: {}:{})", ip, serverHost, serverPort);
            return ip;
        } catch (Exception e) {
            log.debug("라우팅 IP 조회 실패 ({}:{}): {}", serverHost, serverPort, e.getMessage());
            return null;
        }
    }

    /** 지정 IPv4 주소를 보유한 NIC 반환. */
    private NetworkIF findNicByIp(String ip) {
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            nic.updateAttributes();
            for (String addr : nic.getIPv4addr()) {
                if (ip.equals(addr)) return nic;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 키워드 기반 가상 어댑터 필터 (라우팅 탐지 실패 시 fallback)
    // ─────────────────────────────────────────────────────────────────────

    /** VMware·VirtualBox·Hyper-V 등 가상 어댑터 키워드 */
    private static final java.util.List<String> VIRTUAL_KEYWORDS = java.util.List.of(
        "vmware", "vmnet", "virtualbox", "vbox", "hyper-v", "hyperv",
        "tap", "isatap", "teredo", "6to4", "tunnel", "bluetooth",
        "loopback", "pseudo", "miniport", "wan miniport"
    );

    /** 가상 어댑터 여부 판별 (displayName 또는 name 기반). */
    private boolean isVirtualAdapter(NetworkIF nic) {
        String dn = nic.getDisplayName() != null ? nic.getDisplayName().toLowerCase() : "";
        String nm = nic.getName()        != null ? nic.getName().toLowerCase()        : "";
        return VIRTUAL_KEYWORDS.stream().anyMatch(k -> dn.contains(k) || nm.contains(k));
    }

    /**
     * 주요 NIC 선택 (fallback) — 우선순위:
     * 1) 실물 NIC (가상 어댑터 제외) + UP + IP 보유
     * 2) 모든 NIC (가상 포함) + UP + IP 보유  (가상만 있는 환경 fallback)
     * 3) 첫 번째 MAC 보유 NIC
     */
    private NetworkIF getPrimaryNic() {
        // 1순위: 물리 어댑터만 (VMware·VirtualBox 등 제외)
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic) || isVirtualAdapter(nic)) continue;
            nic.updateAttributes();
            if (isInterfaceUsable(nic) && hasAnyIpAddress(nic)) return nic;
        }
        // 2순위: 가상 어댑터 포함 (물리 NIC가 없는 환경 fallback)
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic)) continue;
            nic.updateAttributes();
            if (isInterfaceUsable(nic) && hasAnyIpAddress(nic)) return nic;
        }
        // 3순위: MAC만 있으면 반환
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic)) continue;
            return nic;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────────────

    public String getMacAddress() {
        // 1순위: 라우팅 IP와 일치하는 NIC의 MAC (VMware/VPN 무관하게 항상 정확)
        String routingIp = getRoutingLocalIp();
        if (routingIp != null) {
            synchronized (networkLock) {
                NetworkIF nic = findNicByIp(routingIp);
                if (nic != null && hasValidMac(nic)) {
                    log.debug("MAC 조회 (라우팅 기반): {} NIC={}", nic.getMacaddr(), nic.getDisplayName());
                    return nic.getMacaddr().toUpperCase();
                }
            }
        }
        // 2순위: 키워드 기반 NIC 탐지 (fallback)
        synchronized (networkLock) {
            NetworkIF nic = getPrimaryNic();
            if (nic != null) return nic.getMacaddr().toUpperCase();
        }
        // 3순위: Java 표준 라이브러리
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                byte[] mac = iface.getHardwareAddress();
                if (mac == null || mac.length != 6) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    sb.append(String.format("%02X", mac[i]));
                    if (i < mac.length - 1) sb.append(":");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("MAC 주소 조회 실패: {}", e.getMessage());
        }
        return "00:00:00:00:00:00";
    }

    public String getIpAddress() {
        // 1순위: 백엔드 서버와 실제 통신하는 로컬 IP (OS 라우팅 테이블 기반)
        String routingIp = getRoutingLocalIp();
        if (routingIp != null) return routingIp;
        // 2순위: NIC 기반 탐지 (서버URL 미설정 또는 DNS 실패 시 fallback)
        synchronized (networkLock) {
            NetworkIF nic = getPrimaryNic();
            if (nic != null && nic.getIPv4addr().length > 0) {
                return nic.getIPv4addr()[0];
            }
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public String getHostname() {
        return os.getNetworkParams().getHostName();
    }

    public String getOsInfo() {
        return os.toString();
    }

    /**
     * 이전 측정 이후 인바운드/아웃바운드 Kbps를 계산한다.
     * 최초 호출 시에는 [0.0, 0.0]을 반환하고 기준값을 설정한다.
     */
    public double[] getDeltaKbps() {
        synchronized (networkLock) {
            TrafficCounters counters = readActiveTrafficCounters();
            long now = System.currentTimeMillis();
            if (counters.interfaceCount == 0) {
                // 활성 NIC가 사라진 경우 stale delta가 튀지 않도록 기준값을 초기화한다.
                lastBytesRecv = -1;
                lastBytesSent = -1;
                lastMeasureTime = now;
                return new double[]{0.0, 0.0};
            }

            double inKbps = 0.0, outKbps = 0.0;
            if (lastMeasureTime > 0 && lastBytesRecv >= 0 && lastBytesSent >= 0) {
                double elapsed = (now - lastMeasureTime) / 1000.0;
                if (elapsed > 0) {
                    inKbps = ((counters.totalRecvBytes - lastBytesRecv) * 8.0 / 1000.0) / elapsed;
                    outKbps = ((counters.totalSentBytes - lastBytesSent) * 8.0 / 1000.0) / elapsed;
                    if (inKbps < 0) inKbps = 0;
                    if (outKbps < 0) outKbps = 0;
                }
            }
            lastBytesRecv = counters.totalRecvBytes;
            lastBytesSent = counters.totalSentBytes;
            lastMeasureTime = now;
            return new double[]{inKbps, outKbps};
        }
    }

    private TrafficCounters readActiveTrafficCounters() {
        long totalRecv = 0L;
        long totalSent = 0L;
        int activeCount = 0;

        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic)) continue;
            nic.updateAttributes();
            if (!isTrafficCandidate(nic)) continue;

            totalRecv += Math.max(0L, nic.getBytesRecv());
            totalSent += Math.max(0L, nic.getBytesSent());
            activeCount++;
        }

        return new TrafficCounters(totalRecv, totalSent, activeCount);
    }

    private boolean hasValidMac(NetworkIF nic) {
        String mac = nic.getMacaddr();
        return mac != null && mac.length() == 17 && !mac.isBlank(); // AA:BB:CC:DD:EE:FF
    }

    private boolean hasAnyIpAddress(NetworkIF nic) {
        return (nic.getIPv4addr() != null && nic.getIPv4addr().length > 0)
                || (nic.getIPv6addr() != null && nic.getIPv6addr().length > 0);
    }

    private boolean isTrafficCandidate(NetworkIF nic) {
        return isInterfaceUsable(nic) && hasAnyIpAddress(nic);
    }

    private boolean isInterfaceUsable(NetworkIF nic) {
        try {
            NetworkInterface iface = NetworkInterface.getByName(nic.getName());
            if (iface == null && nic.getIndex() > 0) {
                iface = NetworkInterface.getByIndex(nic.getIndex());
            }
            if (iface == null) {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces != null && ifaces.hasMoreElements()) {
                    NetworkInterface candidate = ifaces.nextElement();
                    if (candidate.getDisplayName() != null
                            && candidate.getDisplayName().equalsIgnoreCase(nic.getDisplayName())) {
                        iface = candidate;
                        break;
                    }
                }
            }
            if (iface == null) {
                // 플랫폼별 이름 매칭 실패 시에는 과도한 0 트래픽을 방지하기 위해 낙관적으로 포함한다.
                return true;
            }
            return iface.isUp() && !iface.isLoopback();
        } catch (Exception e) {
            log.debug("NIC 상태 확인 실패 name={} idx={} message={}",
                    nic.getName(), nic.getIndex(), e.getMessage());
            return true;
        }
    }

    private record TrafficCounters(long totalRecvBytes, long totalSentBytes, int interfaceCount) {
    }
}
