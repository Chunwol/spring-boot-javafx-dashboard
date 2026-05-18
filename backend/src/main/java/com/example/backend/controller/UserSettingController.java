package com.example.backend.controller;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 사용자 UI 설정을 키-값으로 저장/조회한다.
 *
 * <p>설정값은 단순 문자열로 관리하며 프론트엔드 테마, 레이아웃 등의 개인화 데이터를 보관한다.
 * <ul>
 *   <li>GET  /api/v1/settings/{key}  → {"key":"...", "value":"..."}</li>
 *   <li>PUT  /api/v1/settings/{key}  body {"value":"..."} → 204</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
@Tag(name = "User Settings", description = "사용자 UI 설정 키-값 저장/조회 API")
public class UserSettingController {

    private final UserSettingRepository repo;

    public UserSettingController(UserSettingRepository repo) {
        this.repo = repo;
    }

    /**
     * 설정값 조회.
     * 존재하지 않는 키는 빈 문자열을 반환한다.
     */
    @GetMapping("/{key}")
    @Operation(summary = "설정값 조회", description = "키에 해당하는 UI 설정값을 조회합니다. 없으면 빈 문자열 반환.")
    @ApiResponse(responseCode = "200", description = "설정값 조회 성공")
    public ResponseEntity<Map<String, String>> get(
            @Parameter(description = "설정 키", example = "theme") @PathVariable String key) {
        String value = repo.findById(key).map(UserSetting::getValue).orElse("");
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    /**
     * 설정값 저장/갱신.
     * 키가 없으면 새로 생성하고, 있으면 덮어쓴다.
     */
    @PutMapping("/{key}")
    @Operation(summary = "설정값 저장", description = "키에 해당하는 UI 설정값을 저장합니다. 없으면 생성, 있으면 갱신.")
    @ApiResponse(responseCode = "204", description = "저장 성공")
    public ResponseEntity<Void> put(
            @Parameter(description = "설정 키", example = "theme") @PathVariable String key,
            @RequestBody Map<String, String> body) {
        String value = body.getOrDefault("value", "");
        UserSetting s = repo.findById(key).orElse(new UserSetting(key, ""));
        s.setValue(value);
        repo.save(s);
        return ResponseEntity.noContent().build();
    }
}
