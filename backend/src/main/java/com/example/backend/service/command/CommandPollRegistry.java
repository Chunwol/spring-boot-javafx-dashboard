package com.example.backend.service.command;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-poll 대기 중인 에이전트들의 DeferredResult를 관리한다.
 * 명령이 큐에 삽입되면 해당 MAC의 대기 연결에 즉시 응답을 보낼 수 있다.
 */
@Component
public class CommandPollRegistry {

    // key: 정규화된 MAC (대문자, 구분자 제거)
    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<?>>> waiters =
            new ConcurrentHashMap<>();

    /**
     * 에이전트가 대기 연결을 등록한다.
     * 타임아웃 시 자동으로 204 No Content 반환.
     */
    public DeferredResult<ResponseEntity<?>> register(String canonicalMac, long timeoutMs) {
        DeferredResult<ResponseEntity<?>> deferred =
                new DeferredResult<>(timeoutMs, ResponseEntity.noContent().build());
        waiters.put(canonicalMac, deferred);
        deferred.onCompletion(() -> waiters.remove(canonicalMac, deferred));
        deferred.onTimeout(()   -> waiters.remove(canonicalMac, deferred));
        return deferred;
    }

    /**
     * 해당 MAC의 대기 중인 DeferredResult를 꺼낸다. 없으면 null.
     * 호출자가 즉시 결과를 세팅해야 한다.
     */
    public DeferredResult<ResponseEntity<?>> take(String canonicalMac) {
        return waiters.remove(canonicalMac);
    }
}
