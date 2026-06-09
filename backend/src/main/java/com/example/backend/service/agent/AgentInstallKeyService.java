package com.example.backend.service.agent;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentInstallKeyService {

    private static final String SETTING_KEY_INSTALL_KEY = "agent.install-key.override";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserSettingRepository userSettingRepository;

    @Value("${agent.install-key:INST-CHANGE-ME-BEFORE-DEPLOY}")
    private String defaultInstallKey;

    public String getInstallKey() {
        return userSettingRepository.findById(SETTING_KEY_INSTALL_KEY)
                .map(UserSetting::getValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(defaultInstallKey);
    }

    public Map<String, Object> describeInstallKey() {
        return describeInstallKey(getInstallKey());
    }

    @Transactional
    public Map<String, Object> rotateInstallKey() {
        String nextKey = generateInstallKey();
        UserSetting setting = userSettingRepository.findById(SETTING_KEY_INSTALL_KEY)
                .orElse(new UserSetting(SETTING_KEY_INSTALL_KEY, ""));
        setting.setValue(nextKey);
        userSettingRepository.save(setting);
        return describeInstallKey(nextKey);
    }

    private Map<String, Object> describeInstallKey(String key) {
        boolean configured = isConfigured(key);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("configured", configured);
        resp.put("keyPreview", preview(key));
        resp.put("key", configured ? key : "-");
        return resp;
    }

    private boolean isConfigured(String key) {
        return key != null && !key.isBlank()
                && !key.startsWith("INST-CHANGE-ME")
                && !key.startsWith("CHANGE_ME");
    }

    private String preview(String key) {
        return key != null && key.length() > 4 ? key.substring(0, 4) + "****" : "****";
    }

    private String generateInstallKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "INST-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
