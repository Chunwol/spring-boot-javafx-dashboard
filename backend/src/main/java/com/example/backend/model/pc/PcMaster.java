package com.example.backend.model.pc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "pc_master")
public class PcMaster {

    @Id
    @Column(name = "mac_address", nullable = false, length = 50)
    private String macAddress;

    @Column(name = "pc_name")
    private String pcName;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "status")
    private String status;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "role")
    private String role;

    public PcMaster() {
    }

    // getters and setters
    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getPcName() {
        return pcName;
    }

    public void setPcName(String pcName) {
        this.pcName = pcName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
