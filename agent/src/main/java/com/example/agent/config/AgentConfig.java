package com.example.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * %APPDATA%\PCAgent\config.json 구조.
 * agentToken이 비어 있으면 최초 등록 플로우를 시작한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private String serverUrl = "http://localhost:8888";
    private String installKey = "";
    private String agentToken = "";
    private String tlsPinSha256 = "";
    private int heartbeatIntervalSec = 30;
    private int jitterSec = 5;

    // ----- getters / setters -----

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getInstallKey() { return installKey; }
    public void setInstallKey(String installKey) { this.installKey = installKey; }

    public String getAgentToken() { return agentToken; }
    public void setAgentToken(String agentToken) { this.agentToken = agentToken; }

    public String getTlsPinSha256() { return tlsPinSha256; }
    public void setTlsPinSha256(String tlsPinSha256) { this.tlsPinSha256 = tlsPinSha256; }

    public int getHeartbeatIntervalSec() { return heartbeatIntervalSec; }
    public void setHeartbeatIntervalSec(int heartbeatIntervalSec) {
        this.heartbeatIntervalSec = heartbeatIntervalSec;
    }

    public int getJitterSec() { return jitterSec; }
    public void setJitterSec(int jitterSec) { this.jitterSec = jitterSec; }
}
