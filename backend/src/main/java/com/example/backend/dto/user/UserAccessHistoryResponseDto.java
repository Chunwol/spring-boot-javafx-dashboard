package com.example.backend.dto.user;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserAccessHistoryResponseDto {
    private Long id;
    private String username;
    private String eventType;
    private String ipAddress;
    private String userAgent;
    private String detailJson;
    private LocalDateTime occurredAt;

}
