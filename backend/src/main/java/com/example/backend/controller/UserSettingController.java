package com.example.backend.controller;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 사용자 UI 설정을 키-값으로 저장/조회한다.
 * GET  /api/v1/settings/{key}          → {"key":"...", "value":"..."}
 * PUT  /api/v1/settings/{key}          body {"value":"..."} → 204
 */
@RestController
@RequestMapping("/api/v1/settings")
public class UserSettingController {

    private final UserSettingRepository repo;

    public UserSettingController(UserSettingRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, String>> get(@PathVariable String key) {
        String value = repo.findById(key).map(UserSetting::getValue).orElse("");
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> put(@PathVariable String key,
                                    @RequestBody Map<String, String> body) {
        String value = body.getOrDefault("value", "");
        UserSetting s = repo.findById(key).orElse(new UserSetting(key, ""));
        s.setValue(value);
        repo.save(s);
        return ResponseEntity.noContent().build();
    }
}
