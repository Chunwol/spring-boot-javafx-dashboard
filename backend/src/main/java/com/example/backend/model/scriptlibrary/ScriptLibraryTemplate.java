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
@Table(name = "script_library_template")
public class ScriptLibraryTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel;

    @Column(name = "requires_reboot", nullable = false)
    private Boolean requiresReboot = false;

    @Column(name = "requires_operator_mode", nullable = false)
    private Boolean requiresOperatorMode = false;

    @Column(name = "powershell_requirement", nullable = false, length = 32)
    private String powershellRequirement = "WINDOWS_POWERSHELL_5_1";

    @Column(name = "success_marker", length = 300)
    private String successMarker;

    @Column(name = "built_in_key", length = 80)
    private String builtInKey;

    @Column(name = "inverse_template_id")
    private Long inverseTemplateId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 1;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime updatedAt;
}
