package com.example.backend.config;

import com.example.backend.service.agent.AgentTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 에이전트(PC 클라이언트) 전용 API 엔드포인트를 보호하는 사전 인터셉터.
 *
 * <p>동작 흐름:
 * <ol>
 *   <li>HTTP 요청 헤더 {@code X-Agent-Token}에서 토큰을 추출한다.</li>
 *   <li>{@link AgentTokenService#validateToken(String)}으로 DB에 등록된 유효한 토큰인지 검증한다.</li>
 *   <li>검증 성공 시 {@code request} attribute {@code "agentMac"}에 해당 PC의 MAC 주소를 저장하여
 *       이후 컨트롤러/서비스에서 발신 PC를 식별할 수 있게 한다.</li>
 *   <li>검증 실패(토큰 없음·만료·위조) 시 HTTP 401 응답을 즉시 반환하고 요청 처리를 중단한다.</li>
 * </ol>
 *
 * <p>WHY: JWT 대신 별도 에이전트 토큰을 사용하는 이유는 에이전트가 사용자 세션 없이
 * 데몬 방식으로 동작하기 때문이다. 에이전트 토큰은 설치 시 1회 발급되며 DB에서 관리된다.
 *
 * @see AgentTokenService
 * @see com.example.backend.config.WebMvcConfig 인터셉터 경로 매핑
 */
@Component
@RequiredArgsConstructor
public class AgentTokenInterceptor implements HandlerInterceptor {

    private final AgentTokenService agentTokenService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 헤더에서 에이전트 토큰 추출
        String token = request.getHeader("X-Agent-Token");

        // AgentTokenService를 통해 DB 검증 — 유효하면 MAC 주소 반환
        Optional<String> mac = agentTokenService.validateToken(token);
        if (mac.isEmpty()) {
            // 토큰이 없거나 유효하지 않으면 즉시 401 반환, 필터 체인 진행 차단
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or missing agent token\"}");
            return false;
        }

        // 검증 성공: 이후 컨트롤러에서 agentMac 속성으로 발신 PC를 식별
        request.setAttribute("agentMac", mac.get());
        return true;
    }
}
