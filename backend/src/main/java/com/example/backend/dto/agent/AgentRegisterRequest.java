package com.example.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AgentRegisterRequest {
    @NotBlank(message = "macAddress는 필수입니다.")
    @Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$", message = "macAddress 형식이 올바르지 않습니다.")
    private String macAddress;

    @NotBlank(message = "hostname은 필수입니다.")
    @Size(max = 100, message = "hostname은 100자를 초과할 수 없습니다.")
    private String hostname;

    @NotBlank(message = "ipAddress는 필수입니다.")
    @Pattern(regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$", message = "ipAddress는 IPv4 형식이어야 합니다.")
    private String ipAddress;

    @Size(max = 200, message = "osInfo는 200자를 초과할 수 없습니다.")
    private String osInfo;

    @Size(max = 40, message = "agentVersion은 40자를 초과할 수 없습니다.")
    private String agentVersion;
}
