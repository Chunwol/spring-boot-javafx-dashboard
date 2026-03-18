package com.example.backend.config;

import com.example.backend.service.agent.AgentTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * X-Agent-Token 헤더를 검증하는 인터셉터.
 * 검증 성공 시 request attribute "agentMac"에 MAC 주소를 저장.
 * 검증 실패 시 401 반환.
 */
@Component
@RequiredArgsConstructor
public class AgentTokenInterceptor implements HandlerInterceptor {

    private final AgentTokenService agentTokenService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String token = request.getHeader("X-Agent-Token");
        Optional<String> mac = agentTokenService.validateToken(token);
        if (mac.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or missing agent token\"}");
            return false;
        }
        request.setAttribute("agentMac", mac.get());
        return true;
    }
}
