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
@Table(name = "script_library_execution_target")
public class ScriptLibraryExecutionTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "mac_address", nullable = false, length = 64)
    private String macAddress;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;
}
