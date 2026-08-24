package com.example.backend.controller.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.assertThat;

class WebFrontendControllerTest {

    @Test
    void forwardsBrowserRoutesToViteEntryPoint() throws NoSuchMethodException {
        assertThat(new WebFrontendController().webFrontend()).isEqualTo("forward:/index.html");
        GetMapping mapping = WebFrontendController.class
                .getMethod("webFrontend")
                .getAnnotation(GetMapping.class);
        assertThat(mapping.value()).contains("/script-library");
    }
}
