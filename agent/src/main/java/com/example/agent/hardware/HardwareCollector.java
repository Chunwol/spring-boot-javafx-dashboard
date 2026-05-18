package com.example.agent.hardware;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
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

    /** 주요 NIC 선택 (업 상태 + IP 보유 NIC 우선) */
    private NetworkIF getPrimaryNic() {
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic)) continue;
            nic.updateAttributes();
            if (isInterfaceUsable(nic) && hasAnyIpAddress(nic)) {
                return nic;
            }
        }
        // fallback: 기존 동작과의 호환을 위해 "첫 번째 물리 NIC"도 허용
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (!hasValidMac(nic)) continue;
            nic.updateAttributes();
            return nic;
        }
        return null;
    }

    public String getMacAddress() {
        synchronized (networkLock) {
            NetworkIF nic = getPrimaryNic();
            if (nic != null) return nic.getMacaddr().toUpperCase();
        }
        // fallback: Java standard library
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
