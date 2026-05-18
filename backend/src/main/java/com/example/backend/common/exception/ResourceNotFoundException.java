package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청된 리소스가 존재하지 않을 때 던져지는 예외.
 *
 * <p>사용 사례:
 * <ul>
 *   <li>존재하지 않는 PC({@code PcMaster}) ID로 조회·수정·삭제</li>
 *   <li>등록되지 않은 강의실 그룹({@code ClassroomGroup}) ID 참조</li>
 *   <li>DB에서 삭제된 알림({@code Alert}) 상세 조회</li>
 * </ul>
 *
 * <p>HTTP 404 Not Found로 매핑된다.
 */
public class ResourceNotFoundException extends ApplicationException {

    private static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(CODE, message, HttpStatus.NOT_FOUND.value());
    }

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(CODE,
              String.format("%s을(를) 찾을 수 없습니다: %s", resourceType, resourceId),
              HttpStatus.NOT_FOUND.value());
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(CODE, message, HttpStatus.NOT_FOUND.value(), cause);
    }
}
