package com.example.backend.dto.dashboard;

import java.time.LocalDateTime;

public class PcStatusDto {
    private String macAddress;
    private String pcName;
    private String ipAddress;
    private Long groupId;
    private String groupName;
    private String status;
    private boolean online;
    private String role;
    private String brokenNote;
    private LocalDateTime lastSeen;

    public PcStatusDto() {}

    // getters and setters
    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }
    public String getPcName() { return pcName; }
    public void setPcName(String pcName) { this.pcName = pcName; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getBrokenNote() { return brokenNote; }
    public void setBrokenNote(String brokenNote) { this.brokenNote = brokenNote; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
}
