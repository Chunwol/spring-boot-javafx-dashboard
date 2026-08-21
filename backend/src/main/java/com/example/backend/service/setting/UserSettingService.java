package com.example.backend.service.setting;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSettingService {

    private final UserSettingRepository userSettingRepository;

    public String get(String username, String key) {
        return userSettingRepository.findById(scopedKey(username, key))
                .map(UserSetting::getValue)
                .orElse("");
    }

    @Transactional
    public void save(String username, String key, String value) {
        String storageKey = scopedKey(username, key);
        UserSetting s = userSettingRepository.findById(storageKey)
                .orElse(new UserSetting(storageKey, ""));
        s.setValue(value);
        userSettingRepository.save(s);
    }

    private String scopedKey(String username, String key) {
        if (username == null || username.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Username and setting key are required");
        }
        return username.trim().toLowerCase(Locale.ROOT) + ":" + key.trim();
    }
}
