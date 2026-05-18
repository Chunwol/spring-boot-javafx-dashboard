package com.example.backend.service.command;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-poll 방식으로 대기 중인 에이전트 연결({@link DeferredResult})을 관리하는 레지스트리.
 *
 * <p><b>동작 원리</b>: 에이전트는 {@link #register}로 연결을 등록하고 서버 응답을 블로킹 대기한다.
 * 명령이 큐에 삽입되면 {@link #take}로 대기 연결을 꺼내 즉시 응답함으로써
 * 폴링 지연 없이 실시간에 가까운 명령 전달을 구현한다.
 *
 * <p><b>스레드 안전성</b>:
 * <ul>
 *   <li>{@link ConcurrentHashMap}을 사용하여 다중 HTTP 스레드에서 동시 register/take 호출을 안전하게 처리한다.</li>
 *   <li>{@code remove(key, value)} 형태의 원자적 제거로, 타임아웃/완료 콜백과 take() 간의 경쟁 조건을 방지한다.
 *       (같은 MAC 주소로 새 연결이 등록된 경우 이전 콜백이 신규 항목을 잘못 삭제하지 않는다.)</li>
 *   <li>동일 MAC에서 새 연결이 들어오면 이전 {@link DeferredResult}를 덮어쓴다. 이전 연결은 타임아웃 대기.</li>
 * </ul>
 */
@Component
public class CommandPollRegistry {

    // key: 정규화된 MAC 주소 (대문자, 구분자 제거 형태)
    // ConcurrentHashMap: register/take 동시 호출에 대한 스레드 안전성 보장
    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<?>>> waiters =
            new ConcurrentHashMap<>();

    /**
     * 에이전트의 long-poll 대기 연결을 등록한다.
     *
     * <p>타임아웃 발생 시 자동으로 204 No Content를 반환하고 레지스트리에서 제거된다.
     * 완료 콜백({@code onCompletion})은 정상 응답, 타임아웃, 에러 모든 경우에 호출된다.
     *
     * <p><b>스레드 안전</b>: {@code onCompletion}/{@code onTimeout} 콜백은 Spring MVC 내부 스레드에서
     * 호출될 수 있으므로 {@code ConcurrentHashMap.remove(key, value)} 원자 연산으로 처리한다.
     *
     * @param canonicalMac 정규화된 MAC 주소 (대문자, 구분자 제거)
     * @param timeoutMs    long-poll 최대 대기 시간 (밀리초)
     * @return 등록된 {@link DeferredResult} (Controller에서 반환)
     */
    public DeferredResult<ResponseEntity<?>> register(String canonicalMac, long timeoutMs) {
        DeferredResult<ResponseEntity<?>> deferred =
                new DeferredResult<>(timeoutMs, ResponseEntity.noContent().build());
        waiters.put(canonicalMac, deferred);
        // 완료/타임아웃 시 원자적으로 제거하여 메모리 누수 방지
        deferred.onCompletion(() -> waiters.remove(canonicalMac, deferred));
        deferred.onTimeout(()   -> waiters.remove(canonicalMac, deferred));
        return deferred;
    }

    /**
     * 해당 MAC 주소의 대기 중인 {@link DeferredResult}를 레지스트리에서 꺼낸다.
     *
     * <p>반환 값이 null이 아닌 경우 호출자는 즉시 결과를 세팅해야 한다.
     * 결과 세팅 후 Spring MVC가 HTTP 응답을 완료한다.
     *
     * @param canonicalMac 정규화된 MAC 주소
     * @return 대기 중인 {@link DeferredResult}, 없으면 {@code null}
     */
    public DeferredResult<ResponseEntity<?>> take(String canonicalMac) {
        return waiters.remove(canonicalMac);
    }
}
