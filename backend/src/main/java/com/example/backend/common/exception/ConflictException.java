package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청이 현재 리소스 상태 또는 기존 데이터와 충돌할 때 던져지는 예외.
 *
 * <p>사용 사례:
 * <ul>
 *   <li>이미 등록된 MAC 주소로 에이전트를 재등록하려는 경우</li>
 *   <li>동일 이름의 강의실 그룹을 중복 생성하려는 경우</li>
 *   <li>낙관적 잠금(Optimistic Locking) 충돌</li>
 * </ul>
 *
 * <p>HTTP 409 Conflict로 매핑된다.
 */
public class ConflictException extends ApplicationException {

    private static final String CODE = "CONFLICT";

    public ConflictException(String message) {
        super(CODE, message, HttpStatus.CONFLICT.value());
    }

    public ConflictException(String entityType, String conflictReason) {
        super(CODE,
              String.format("%s 충돌: %s", entityType, conflictReason),
              HttpStatus.CONFLICT.value());
    }

    public ConflictException(String message, Throwable cause) {
        super(CODE, message, HttpStatus.CONFLICT.value(), cause);
    }
}
