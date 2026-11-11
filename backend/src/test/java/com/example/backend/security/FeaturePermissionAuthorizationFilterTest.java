package com.example.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FeaturePermissionAuthorizationFilterTest {

    private final FeaturePermissionAuthorizationFilter filter = new FeaturePermissionAuthorizationFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnApiFromAnUncheckedFeature() throws Exception {
        authenticate("PERM_NETWORK_VIEW");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/monthly");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void permitsTheApiForAnAssignedFeature() throws Exception {
        authenticate("PERM_NETWORK_VIEW");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/traffic");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void viewPermissionCannotCallAWriteActionInTheSameModule() throws Exception {
        authenticate("PERM_TOKEN_VIEW");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/admin/install-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void matchingDetailedWritePermissionPermitsTheAction() throws Exception {
        authenticate("PERM_INSTALL_KEY_ROTATE");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/admin/install-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void deviceNoteEditorCanUpdateADeviceNote() throws Exception {
        authenticate("PERM_BROKEN_PC_REGISTER_EDIT");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/dashboard/pcs/AA-BB-CC-DD-EE-FF/note");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void pcViewerCannotUpdateADeviceNote() throws Exception {
        authenticate("PERM_PC_VIEW");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/dashboard/pcs/AA-BB-CC-DD-EE-FF/note");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
