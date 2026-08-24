package com.example.backend.model.scriptlibrary;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "script_library_execution")
public class ScriptLibraryExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "template_name_snapshot", nullable = false, length = 120)
    private String templateNameSnapshot;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "script_sha256", nullable = false, length = 64)
    private String scriptSha256;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "run_mode", nullable = false, length = 16)
    private String runMode;

    @Column(name = "window_mode", nullable = false, length = 16)
    private String windowMode;

    @Column(name = "arguments_text", length = 1000)
    private String argumentsText;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "source_execution_id")
    private Long sourceExecutionId;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime updatedAt;
}
