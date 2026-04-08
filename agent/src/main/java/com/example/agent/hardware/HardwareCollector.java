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

    /** 주요 NIC 선택 (루프백 제외, 첫 번째 물리 NIC) */
    private NetworkIF getPrimaryNic() {
        for (NetworkIF nic : hal.getNetworkIFs(false)) {
            if (nic.getMacaddr() == null || nic.getMacaddr().isBlank()) continue;
            if (nic.getMacaddr().length() == 17) {   // AA:BB:CC:DD:EE:FF 형식
                nic.updateAttributes();
                return nic;
            }
        }
        return null;
    }

    public String getMacAddress() {
        NetworkIF nic = getPrimaryNic();
        if (nic != null) return nic.getMacaddr().toUpperCase();
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
        NetworkIF nic = getPrimaryNic();
        if (nic != null && nic.getIPv4addr().length > 0) {
            return nic.getIPv4addr()[0];
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
            NetworkIF nic = getPrimaryNic();
            if (nic == null) return new double[]{0.0, 0.0};

            long now = System.currentTimeMillis();
            long bytesRecv = nic.getBytesRecv();
            long bytesSent = nic.getBytesSent();

            double inKbps = 0.0, outKbps = 0.0;
            if (lastMeasureTime > 0) {
                double elapsed = (now - lastMeasureTime) / 1000.0;
                if (elapsed > 0) {
                    inKbps = ((bytesRecv - lastBytesRecv) * 8.0 / 1000.0) / elapsed;
                    outKbps = ((bytesSent - lastBytesSent) * 8.0 / 1000.0) / elapsed;
                    if (inKbps < 0) inKbps = 0;
                    if (outKbps < 0) outKbps = 0;
                }
            }
            lastBytesRecv = bytesRecv;
            lastBytesSent = bytesSent;
            lastMeasureTime = now;
            return new double[]{inKbps, outKbps};
        }
    }
}
