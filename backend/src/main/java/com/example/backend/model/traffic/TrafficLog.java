package com.example.backend.model.traffic;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "traffic_logs")
public class TrafficLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    @Column(nullable = false)
    private String macAddress;

    @Column(name = "inbound_kbps")
    private Long inboundKbps;

    @Column(name = "outbound_kbps")
    private Long outboundKbps;

    @Column
    private LocalDateTime timestamp;

    public TrafficLog() {
    }

    // getters and setters
    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public Long getInboundKbps() {
        return inboundKbps;
    }

    public void setInboundKbps(Long inboundKbps) {
        this.inboundKbps = inboundKbps;
    }

    public Long getOutboundKbps() {
        return outboundKbps;
    }

    public void setOutboundKbps(Long outboundKbps) {
        this.outboundKbps = outboundKbps;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
