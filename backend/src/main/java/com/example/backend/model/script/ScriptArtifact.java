package com.example.backend.model.script;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 스크립트 아티팩트 엔티티.
 * 관리자가 업로드한 실행 스크립트 파일의 메타데이터를 저장한다.
 * SHA-256 해시를 통해 무결성을 검증하며, 에이전트는 이 URL을 통해 스크립트를 다운로드한다.
 */
@Schema(description = "업로드된 스크립트 파일 메타데이터")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "scripts")
public class ScriptArtifact {

    /** 스크립트 ID (자동 생성) */
    @Schema(description = "스크립트 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 원본 파일명 */
    @Schema(description = "원본 파일명")
    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** 서버에 저장된 파일명 (UUID 기반) */
    @Schema(description = "저장 파일명 (서버 내부 UUID 기반)")
    @Column(name = "stored_filename", nullable = false)
    private String storedFilename;

    /** SHA-256 해시값 (무결성 검증용) */
    @Schema(description = "SHA-256 해시값")
    @Column(name = "sha256", nullable = false)
    private String sha256;

    /** 파일 크기 (바이트) */
    @Schema(description = "파일 크기 (바이트)")
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** MIME 타입 */
    @Schema(description = "MIME 타입")
    @Column(name = "content_type")
    private String contentType;

    /** ADMIN_UPLOAD 또는 AGENT_COLLECTION. */
    @Column(name = "artifact_kind", nullable = false)
    private String artifactKind = "ADMIN_UPLOAD";

    /** 수집 파일을 업로드한 에이전트 MAC. 관리자 업로드는 null. */
    @Column(name = "owner_mac")
    private String ownerMac;

    /** 업로드 일시 */
    @Schema(description = "업로드 일시")
    @Column(name = "created_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;

    public boolean isAgentCollection() {
        return "AGENT_COLLECTION".equalsIgnoreCase(artifactKind);
    }
}
