package com.example.backend.common.error;

import com.example.backend.common.exception.ApplicationException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;

/**
 * 전역 예외 처리기.
 * 모든 REST 컨트롤러에서 발생하는 예외를 일관된 {@link ApiErrorResponse} 형식으로 변환한다.
 *
 * <h3>예외 처리 계층</h3>
 * <ol>
 *   <li>애플리케이션 예외 ({@code common.exception.*}) — HTTP 상태 코드 포함</li>
 *   <li>검증 예외 (Bean Validation, ConstraintViolation) — 필드별 오류 정보 포함</li>
 *   <li>형식/타입 예외 (JSON 파싱 오류, 타입 불일치)</li>
 *   <li>인증/권한 예외</li>
 *   <li>일반 예외 (500 Internal Server Error)</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ======== APPLICATION EXCEPTIONS ========

    /**
     * 공통 애플리케이션 예외 처리.
     * HTTP 상태 코드와 에러 코드를 포함한 응답을 반환한다.
     * — 응답: {@code ex.httpStatus} (기본 500)
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            ApplicationException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                ex.getHttpStatus(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getHttpStatus()).body(resp);
    }

    /**
     * 리소스 미발견 예외 처리 (404).
     * {@code common.exception.ResourceNotFoundException} 전용 핸들러.
     * ApplicationException 핸들러보다 먼저 매칭된다.
     * — 응답: 404 Not Found
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(resp);
    }

    /**
     * 잘못된 요청 예외 처리 (400).
     * {@code common.exception.InvalidRequestException} 전용 핸들러.
     * — 응답: 400 Bad Request
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            InvalidRequestException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * 충돌 예외 처리 (409).
     * {@code common.exception.ConflictException} 전용 핸들러.
     * — 응답: 409 Conflict
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "CONFLICT",
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
    }

    // ======== VALIDATION EXCEPTIONS ========

    /**
     * Bean Validation (@Valid) 실패 처리 (400).
     * 필드별 오류 목록을 응답에 포함한다.
     * — 응답: 400 Bad Request + fieldErrors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "입력값 검증에 실패했습니다.",
                request.getRequestURI());

        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            resp.addFieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue());
        }
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * 파라미터 제약 조건(@Validated) 위반 처리 (400).
     * 경로/쿼리 파라미터 검증 실패 시 필드별 오류를 포함한다.
     * — 응답: 400 Bad Request + fieldErrors
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "요청 파라미터 검증에 실패했습니다.",
                request.getRequestURI());

        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            resp.addFieldError(cv.getPropertyPath().toString(), cv.getMessage(), cv.getInvalidValue());
        }
        return ResponseEntity.badRequest().body(resp);
    }

    // ======== FORMAT/TYPE EXCEPTIONS ========

    /**
     * JSON 파싱 오류 처리 (400).
     * 요청 본문이 올바르지 않은 JSON 형식일 때 발생한다.
     * — 응답: 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST",
                "요청 본문(JSON) 형식이 올바르지 않습니다.",
                request.getRequestURI());
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * 요청 파라미터 타입 불일치 처리 (400).
     * 경로 변수나 쿼리 파라미터가 대상 타입으로 변환되지 않을 때 발생한다.
     * — 응답: 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "TYPE_MISMATCH",
                "요청 파라미터 타입이 올바르지 않습니다: " + ex.getName(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * 잘못된 인자 예외 처리 (400).
     * 서비스 레이어에서 명시적으로 던지는 {@link IllegalArgumentException}을 처리한다.
     * — 응답: 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_ARGUMENT",
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(resp);
    }

    // ======== AUTHORIZATION EXCEPTIONS ========

    /**
     * 인증 실패 예외 처리 (401).
     * JWT 토큰이 없거나 유효하지 않을 때 발생한다.
     * — 응답: 401 Unauthorized
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(
            UnauthorizedException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHORIZED",
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(resp);
    }

    // ======== NOT FOUND EXCEPTIONS ========

    /**
     * 요소 미발견 예외 처리 (404).
     * Java 표준 {@link NoSuchElementException}을 리소스 미발견 응답으로 변환한다.
     * — 응답: 404 Not Found
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNoSuchElement(
            NoSuchElementException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                "요청한 리소스를 찾을 수 없습니다.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(resp);
    }

    // ======== METHOD EXCEPTIONS ========

    /**
     * 지원하지 않는 HTTP 메서드 처리 (405).
     * — 응답: 405 Method Not Allowed
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "METHOD_NOT_ALLOWED",
                "지원하지 않는 HTTP 메서드입니다.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(resp);
    }

    // ======== GENERIC EXCEPTION ========

    /**
     * 처리되지 않은 모든 예외 처리 (500).
     * 예상치 못한 예외는 ERROR 레벨로 로깅하고 내부 오류 응답을 반환한다.
     * — 응답: 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unhandled exception at {} {}", request.getMethod(), request.getRequestURI(), ex);
        ApiErrorResponse resp = ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "서버 내부 오류가 발생했습니다.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
    }
}
