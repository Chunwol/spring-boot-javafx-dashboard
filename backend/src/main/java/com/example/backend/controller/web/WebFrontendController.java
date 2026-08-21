package com.example.backend.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Forwards browser routes to the Vite entry point without intercepting REST or asset requests. */
@Controller
public class WebFrontendController {

    @GetMapping({
            "/",
            "/login",
            "/classrooms",
            "/classrooms/{groupId}",
            "/pcs",
            "/pc", "/pc/monitor", "/pc/commands", "/pc/tokens", "/pc/broken",
            "/commands",
            "/tokens",
            "/broken",
            "/network",
            "/network/{groupId}",
            "/reports",
            "/files",
            "/settings",
            "/settings/classrooms",
            "/settings/ip-ranges", "/settings/teacher-pcs",
            "/settings/users",
            "/settings/system",
            "/forbidden"
    })
    public String webFrontend() {
        return "forward:/index.html";
    }
}
