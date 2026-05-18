package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates AgentConfig parameters to catch configuration errors early.
 * Prevents startup with invalid settings.
 */
public class AgentConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigValidator.class);

    /**
     * Validate configuration
     * @param config Configuration to validate
     * @return List of validation errors (empty if valid)
     */
    public List<String> validate(AgentConfig config) {
        List<String> errors = new ArrayList<>();

        // Validate serverUrl
        if (config.getServerUrl() == null || config.getServerUrl().isBlank()) {
            errors.add("serverUrl is required");
        } else if (!config.getServerUrl().startsWith("http://") && !config.getServerUrl().startsWith("https://")) {
            errors.add("serverUrl must start with http:// or https://");
        }

        // Validate installKey
        // agentToken이 비어 있을 때만 installKey가 필요하다.
        boolean hasAgentToken = config.getAgentToken() != null && !config.getAgentToken().isBlank();
        if (!hasAgentToken && (config.getInstallKey() == null || config.getInstallKey().isBlank())) {
            errors.add("installKey is required when agentToken is empty");
        }

        // Validate heartbeat interval
        if (config.getHeartbeatIntervalSec() <= 0) {
            errors.add("heartbeatIntervalSec must be > 0 (invalid: " + config.getHeartbeatIntervalSec() + ")");
        }
        if (config.getHeartbeatIntervalSec() > 3600) {
            log.warn("heartbeatIntervalSec is very large: {}s (>1h). Consider reducing.", config.getHeartbeatIntervalSec());
        }

        // Validate jitter
        if (config.getJitterSec() < 0) {
            errors.add("jitterSec must be >= 0 (invalid: " + config.getJitterSec() + ")");
        }
        if (config.getJitterSec() >= config.getHeartbeatIntervalSec()) {
            errors.add("jitterSec (" + config.getJitterSec() + ") must be less than heartbeatIntervalSec (" + config.getHeartbeatIntervalSec() + ")");
        }

        return errors;
    }

    /**
     * Validate and log results
     * @param config Configuration to validate
     * @return true if valid, false if there are errors
     */
    public boolean validateAndLog(AgentConfig config) {
        List<String> errors = validate(config);
        if (errors.isEmpty()) {
            log.info("설정 검증 통과 - serverUrl: {}, heartbeatInterval: {}s", config.getServerUrl(), config.getHeartbeatIntervalSec());
            return true;
        } else {
            log.error("설정 검증 실패:");
            errors.forEach(e -> log.error("  - {}", e));
            return false;
        }
    }
}
