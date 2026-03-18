package com.example.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * %APPDATA%\PCAgent\config.json 구조.
 * agentToken이 비어 있으면 최초 등록 플로우를 시작한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private String serverUrl = "http://localhost:8080";
    private String installKey = "";
    private String agentToken = "";
    private int heartbeatIntervalSec = 120;
    private int jitterSec = 30;

    /** OTA 자가 업데이트용 GitHub 저장소 (비워 두면 OTA 비활성화) */
    private String githubOwner = "";
    private String githubRepo = "";

    // ----- getters / setters -----

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getInstallKey() { return installKey; }
    public void setInstallKey(String installKey) { this.installKey = installKey; }

    public String getAgentToken() { return agentToken; }
    public void setAgentToken(String agentToken) { this.agentToken = agentToken; }

    public int getHeartbeatIntervalSec() { return heartbeatIntervalSec; }
    public void setHeartbeatIntervalSec(int heartbeatIntervalSec) {
        this.heartbeatIntervalSec = heartbeatIntervalSec;
    }

    public int getJitterSec() { return jitterSec; }
    public void setJitterSec(int jitterSec) { this.jitterSec = jitterSec; }

    public String getGithubOwner() { return githubOwner; }
    public void setGithubOwner(String githubOwner) { this.githubOwner = githubOwner; }

    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = githubRepo; }
}
