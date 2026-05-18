package com.example.backend.controller.user;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 사용자 관리 API — ADMIN 전용.
 * 사용자 조회, 생성, 수정, 삭제를 처리합니다.
 * 비밀번호는 저장 전 BCrypt로 단방향 해시됩니다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "사용자 관리 API — ADMIN 전용")
public class ApiController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 모든 사용자 목록 조회
     */
    @GetMapping
    @Operation(summary = "사용자 목록 조회", description = "등록된 모든 사용자를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "사용자 목록 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * ID로 특정 사용자 조회
     * @param id 사용자 ID
     * @return 사용자 정보 또는 404
     */
    @GetMapping("/{id}")
    @Operation(summary = "사용자 단건 조회", description = "ID로 특정 사용자를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "사용자 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<User> getUserById(
            @Parameter(description = "사용자 ID") @PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 신규 사용자 생성.
     * 비밀번호는 BCrypt로 해시되어 저장됩니다.
     * @param user 생성할 사용자 정보 (username, password, email 필수)
     */
    @PostMapping
    @Operation(summary = "사용자 생성", description = "새 사용자를 생성합니다. 비밀번호는 BCrypt로 저장됩니다.")
    @ApiResponse(responseCode = "200", description = "사용자 생성 성공")
    @ApiResponse(responseCode = "400", description = "필수 필드 누락 또는 중복 사용자명")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public User createUser(@RequestBody User user) {
        // 평문 비밀번호를 BCrypt로 인코딩하여 DB에 저장
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    /**
     * 사용자 정보 수정.
     * username과 email만 업데이트합니다. 비밀번호 변경은 별도 API를 사용하세요.
     * @param id          수정할 사용자 ID
     * @param userDetails 변경할 사용자 정보
     */
    @PutMapping("/{id}")
    @Operation(
        summary = "사용자 정보 수정",
        description = "사용자의 username과 email을 수정합니다. 비밀번호는 이 엔드포인트에서 변경되지 않습니다."
    )
    @ApiResponse(responseCode = "200", description = "사용자 수정 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<User> updateUser(
            @Parameter(description = "사용자 ID") @PathVariable Long id,
            @RequestBody User userDetails) {
        return userRepository.findById(id)
                .map(user -> {
                    user.setUsername(userDetails.getUsername());
                    user.setEmail(userDetails.getEmail());
                    return ResponseEntity.ok(userRepository.save(user));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 사용자 삭제
     * @param id 삭제할 사용자 ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "사용자 삭제", description = "ID로 사용자를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "사용자 ID") @PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}