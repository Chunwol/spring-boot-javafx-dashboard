package com.example.backend.controller.user;

import com.example.backend.dto.user.UserResponseDto;
import com.example.backend.dto.user.UserAccessHistoryResponseDto;
import com.example.backend.dto.user.UserSaveRequestDto;
import com.example.backend.dto.user.UserUpdateRequestDto;
import com.example.backend.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "사용자 관리 API — ADMIN 전용")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "사용자 목록 조회", description = "등록된 모든 사용자를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "사용자 목록 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public List<UserResponseDto> findAll() {
        return userService.findAll();
    }

    @GetMapping("/access-history")
    @Operation(summary = "사용자 접속 기록 조회", description = "최근 로그인, 로그아웃과 로그인 실패 기록 200건을 조회합니다.")
    public List<UserAccessHistoryResponseDto> findAccessHistory() {
        return userService.findAccessHistory();
    }

    @GetMapping("/{id}")
    @Operation(summary = "사용자 단건 조회", description = "ID로 특정 사용자를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "사용자 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<UserResponseDto> findOne(
            @Parameter(description = "사용자 ID") @PathVariable Long id) {
        return ResponseEntity.ok(userService.findOne(id));
    }

    @PostMapping
    @Operation(summary = "사용자 생성", description = "새 사용자를 생성합니다. 비밀번호는 BCrypt로 저장됩니다.")
    @ApiResponse(responseCode = "201", description = "사용자 생성 성공")
    @ApiResponse(responseCode = "400", description = "필수 필드 누락 또는 중복 사용자명")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public ResponseEntity<UserResponseDto> save(@Valid @RequestBody UserSaveRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.save(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "사용자 정보 수정", description = "사용자의 username과 email을 수정합니다.")
    @ApiResponse(responseCode = "200", description = "사용자 수정 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<UserResponseDto> update(
            @Parameter(description = "사용자 ID") @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequestDto dto) {
        return ResponseEntity.ok(userService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "사용자 삭제", description = "ID로 사용자를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<Void> delete(
            @Parameter(description = "사용자 ID") @PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
