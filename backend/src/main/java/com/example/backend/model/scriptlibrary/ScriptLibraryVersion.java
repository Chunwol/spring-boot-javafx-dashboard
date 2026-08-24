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
@Table(name = "script_library_version")
public class ScriptLibraryVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;
}
