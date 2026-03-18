package com.example.backend.dto.websocket;

public class PcStatusEvent {
    private String eventType = "PC_STATUS";
    private Long groupId;
    private String macAddress;
    private String ipAddress;
    private String pcName;
    private String status;
    private String role;
    private String lastSeen;

    public PcStatusEvent() {}

    public PcStatusEvent(Long groupId, String macAddress, String ipAddress, String pcName, String status, String role, String lastSeen) {
        this.groupId = groupId;
        this.macAddress = macAddress;
        this.ipAddress = ipAddress;
        this.pcName = pcName;
        this.status = status;
        this.role = role;
        this.lastSeen = lastSeen;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getPcName() {
        return pcName;
    }

    public void setPcName(String pcName) {
        this.pcName = pcName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(String lastSeen) {
        this.lastSeen = lastSeen;
    }
}

