package com.example.backend.service.setting;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSettingService {

    private final UserSettingRepository userSettingRepository;

    public String get(String key) {
        return userSettingRepository.findById(key)
                .map(UserSetting::getValue)
                .orElse("");
    }

    @Transactional
    public void save(String key, String value) {
        UserSetting s = userSettingRepository.findById(key)
                .orElse(new UserSetting(key, ""));
        s.setValue(value);
        userSettingRepository.save(s);
    }
}
