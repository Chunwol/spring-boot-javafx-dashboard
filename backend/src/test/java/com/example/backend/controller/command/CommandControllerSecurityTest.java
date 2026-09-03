package com.example.backend.controller.command;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class CommandControllerSecurityTest {

    @Test
    void commandHistoryDeletionIsAdminOnly() throws NoSuchMethodException {
        PreAuthorize preAuthorize = CommandController.class
                .getMethod("deleteCommandHistory", Long.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }
}
