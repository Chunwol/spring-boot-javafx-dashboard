package com.example.backend.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CommandRequestDto {
    @NotBlank(message = "scope는 필수입니다.")
    @Pattern(regexp = "^(?i)(SINGLE|GROUP|MULTI)$", message = "scope는 SINGLE, GROUP 또는 MULTI 이어야 합니다.")
    private String scope; // SINGLE, GROUP, MULTI

    @Pattern(regexp = "^(?i)(TEACHER|STUDENT|ALL)?$", message = "role은 TEACHER/STUDENT/ALL 중 하나여야 합니다.")
    private String role;  // TEACHER, STUDENT, ALL

    @NotBlank(message = "type은 필수입니다.")
    @Pattern(regexp = "^(?i)(SHUTDOWN|REBOOT|RUN_CMD|RUN_PS1|WOL|PCON|COLLECT_FILES|LIST_FILES|AGENT_UPDATE|AGENT_BUNDLE_UPDATE|REMOTE_CONTROL_START|REMOTE_CONTROL_STOP|SCMATE_CONTROL)$", message = "type 값이 올바르지 않습니다.")
    private String type;

    @Pattern(regexp = "^$|^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$", message = "macAddress 형식이 올바르지 않습니다.")
    private String macAddress;

    @Positive(message = "groupId는 1 이상의 값이어야 합니다.")
    private Long groupId;

    @Size(max = 4000, message = "payloadJson은 4000자를 초과할 수 없습니다.")
    private String payloadJson;

    @Size(max = 500, message = "scriptUrl은 500자를 초과할 수 없습니다.")
    private String scriptUrl;

    @Size(max = 2000, message = "targetMacs는 최대 2000개까지 지정할 수 있습니다.")
    private List<@Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$",
            message = "targetMacs 항목의 MAC 형식이 올바르지 않습니다.") String> targetMacs;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getScriptUrl() {
        return scriptUrl;
    }

    public void setScriptUrl(String scriptUrl) {
        this.scriptUrl = scriptUrl;
    }

    public List<String> getTargetMacs() {
        return targetMacs;
    }

    public void setTargetMacs(List<String> targetMacs) {
        this.targetMacs = targetMacs;
    }

    /** WOL 릴레이 대상 서브넷 브로드캐스트 IP (예: 192.168.10.255). null이면 255.255.255.255 사용 */
    @Pattern(regexp = "^$|^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
             message = "broadcastIp 형식이 올바르지 않습니다.")
    private String broadcastIp;

    /** 관리자 작업 여부 (파일 설치, 에이전트 업데이트 등) - SCMATE 초기화 감지용 */
    private Boolean isAdminOperation = false;

    public String getBroadcastIp() {
        return broadcastIp;
    }

    public void setBroadcastIp(String broadcastIp) {
        this.broadcastIp = broadcastIp;
    }

    public Boolean getIsAdminOperation() {
        return isAdminOperation != null ? isAdminOperation : false;
    }

    public void setIsAdminOperation(Boolean adminOperation) {
        isAdminOperation = adminOperation;
    }
}
