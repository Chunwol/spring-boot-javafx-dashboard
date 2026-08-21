package com.example.backend.controller.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebFrontendControllerTest {

    @Test
    void forwardsBrowserRoutesToViteEntryPoint() {
        assertThat(new WebFrontendController().webFrontend()).isEqualTo("forward:/index.html");
    }
}
