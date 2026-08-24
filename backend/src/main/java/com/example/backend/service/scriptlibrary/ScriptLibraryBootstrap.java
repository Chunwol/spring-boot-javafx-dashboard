package com.example.backend.service.scriptlibrary;

import com.example.backend.model.scriptlibrary.ScriptLibraryTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScriptLibraryBootstrap implements ApplicationRunner {
    private final ScriptLibraryService service;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ScriptLibraryTemplate block = service.ensureBuiltIn(
                "WINDOWS_UPDATE_BLOCK",
                "Windows Update 자동 업데이트 차단",
                "현재 Windows의 업데이트 정책과 서비스 시작 형식을 백업한 뒤 자동 업데이트를 차단합니다. 복구 시점 저장은 별도입니다.",
                "Windows Update",
                "HIGH",
                false,
                true,
                "SCRIPT_LIBRARY_WINDOWS_UPDATE_BLOCK verified=True",
                "windows-update-block.ps1",
                resource("windows-update-block.ps1"));
        ScriptLibraryTemplate unblock = service.ensureBuiltIn(
                "WINDOWS_UPDATE_UNBLOCK",
                "Windows Update 차단 해제",
                "차단 스크립트가 저장한 백업을 기준으로 Windows Update 정책과 서비스 시작 형식을 복원합니다.",
                "Windows Update",
                "HIGH",
                false,
                true,
                "SCRIPT_LIBRARY_WINDOWS_UPDATE_UNBLOCK verified=True",
                "windows-update-unblock.ps1",
                resource("windows-update-unblock.ps1"));
        service.linkInverse(block.getId(), unblock.getId());
    }

    private byte[] resource(String name) throws Exception {
        return new ClassPathResource("script-library/" + name).getInputStream().readAllBytes();
    }
}
