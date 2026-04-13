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
    private String githubOwner = "";
    private String githubRepo = "";
    private String internetCheckHost = "1.1.1.1";
    private int internetCheckPort = 53;
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

    public String getGithubOwner() { return githubOwner; }
    public void setGithubOwner(String githubOwner) { this.githubOwner = githubOwner; }

    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = githubRepo; }

    public String getInternetCheckHost() { return internetCheckHost; }
    public void setInternetCheckHost(String internetCheckHost) { this.internetCheckHost = internetCheckHost; }

    public int getInternetCheckPort() { return internetCheckPort; }
    public void setInternetCheckPort(int internetCheckPort) { this.internetCheckPort = internetCheckPort; }
}
