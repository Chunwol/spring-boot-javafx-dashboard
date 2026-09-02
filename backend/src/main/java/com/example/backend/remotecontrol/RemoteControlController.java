package com.example.backend.remotecontrol;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/remote-control/sessions")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_REMOTE_SCREEN_VIEW')")
public class RemoteControlController {

    private final RemoteControlSessionService sessionService;

    public RemoteControlController(RemoteControlSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<RemoteControlSessionView> create(
            @Valid @RequestBody RemoteControlSessionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        RemoteControlSessionView view = sessionService.createSession(
                request.macAddress(), request.taskId(), authentication.getName(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
        HttpStatus status = "PENDING".equals(view.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping("/active")
    public List<ActiveRemoteControlSessionView> active() {
        return sessionService.listActiveSessions();
    }

    @GetMapping("/{sessionId}")
    public RemoteControlSessionView get(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        return sessionService.getSession(sessionId, authentication.getName());
    }

    @PostMapping("/{sessionId}/ticket")
    public RemoteControlTicketResponse issueTicket(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        return sessionService.issueTicket(sessionId, authentication.getName());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        sessionService.closeSession(sessionId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-close")
    public ResponseEntity<Void> closeBatch(
            @Valid @RequestBody RemoteControlBatchCloseRequest request,
            Authentication authentication) {
        sessionService.closeSessions(request.sessionIds(), authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
