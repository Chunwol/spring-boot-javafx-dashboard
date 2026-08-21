package com.example.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDistributionRequestSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsJwtAuthenticatedAdministratorWithoutExposingSharedSecretToBrowser() {
        FileDistributionRequestSecurity security = enabledSecurity();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "token", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertDoesNotThrow(() -> security.validateOrThrow(new MockHttpServletRequest(), "createJob|payload"));
    }

    @Test
    void stillRequiresSignatureForNonUserIntegration() {
        FileDistributionRequestSecurity security = enabledSecurity();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);

        assertThrows(IllegalArgumentException.class,
                () -> security.validateOrThrow(request, "createJob|payload"));
    }

    private FileDistributionRequestSecurity enabledSecurity() {
        FileDistributionRequestSecurity security = new FileDistributionRequestSecurity();
        ReflectionTestUtils.setField(security, "enabled", true);
        ReflectionTestUtils.setField(security, "requireHttps", true);
        ReflectionTestUtils.setField(security, "maxSkewSeconds", 60L);
        ReflectionTestUtils.setField(security, "sharedSecret", "test-secret");
        return security;
    }
}
