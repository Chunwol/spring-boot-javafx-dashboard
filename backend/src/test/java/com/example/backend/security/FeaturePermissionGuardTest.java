package com.example.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeaturePermissionGuardTest {

    private final FeaturePermissionGuard guard = new FeaturePermissionGuard();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void distinguishesPowerCommands() {
        authenticate("PERM_POWER_ON");

        assertThatCode(() -> guard.requireCommand("PCON")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireCommand("SHUTDOWN")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void distinguishesScmateActions() {
        authenticate("PERM_SCMATE_BASELINE_UPDATE");

        assertThatCode(() -> guard.requireScmateAction("UPDATE_BASELINE")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireScmateAction("RESTORE_BASELINE"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
