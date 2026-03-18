package com.example.frontend.model.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IpRangeItem {
    private Long id;
    private Long groupId;
    private String groupName;
    private String cidr;
    private String startIp;
    private String endIp;
    private String description;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }
    public String getStartIp() { return startIp; }
    public void setStartIp(String startIp) { this.startIp = startIp; }
    public String getEndIp() { return endIp; }
    public void setEndIp(String endIp) { this.endIp = endIp; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
