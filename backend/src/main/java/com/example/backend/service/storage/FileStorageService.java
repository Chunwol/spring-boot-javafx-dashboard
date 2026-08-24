package com.example.backend.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 파일 업로드/다운로드를 위한 로컬 파일 시스템 스토리지 서비스.
 *
 * <p>업로드 루트 디렉토리는 {@code app.scripts.dir} 프로퍼티로 설정한다 (기본: {@code uploads}).
 *
 * <p><b>보안</b>: 저장 및 로드 시 Path Traversal 공격을 방어한다.
 * resolve 후 정규화된 경로가 rootLocation 하위인지 검증한다.
 *
 * <p>DB를 접근하지 않으므로 트랜잭션이 불필요하다.
 */
@Service
public class FileStorageService {

    private final Path rootLocation;

    /**
     * 스토리지 서비스를 초기화한다.
     * 업로드 디렉토리가 존재하지 않으면 자동 생성한다.
     *
     * @param uploadDir 업로드 루트 디렉토리 경로 (상대/절대 경로 모두 허용)
     * @throws RuntimeException 디렉토리 생성 실패 시
     */
    public FileStorageService(@Value("${app.scripts.dir:uploads}") String uploadDir) {
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    /**
     * 멀티파트 파일을 스토리지에 저장하고 저장된 파일명을 반환한다.
     *
     * <p>원본 파일명을 sanitize하여 경로 구분자와 특수문자를 제거한 뒤
     * UUID 접두사를 붙여 충돌을 방지한다.
     *
     * @param file 업로드된 멀티파트 파일
     * @return 저장된 파일명 ({@code UUID_sanitizedOriginalName} 형식)
     * @throws RuntimeException 빈 파일, Path Traversal 시도, 또는 IO 오류 시
     */
    public String store(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new RuntimeException("Failed to store empty file.");
            }
            // 원본 파일명 sanitize: 경로 구분자, 특수문자 제거
            String originalFilename = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "file";
            String safeFilename = Paths.get(originalFilename).getFileName().toString()
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = UUID.randomUUID() + "_" + safeFilename;

            // Path Traversal 방어: resolve 후 rootLocation 하위인지 검증
            Path target = rootLocation.resolve(filename).normalize();
            if (!target.startsWith(rootLocation)) {
                throw new RuntimeException("Invalid file path: " + filename);
            }
            Files.copy(file.getInputStream(), target);
            return filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file.", e);
        }
    }

    /**
     * 백엔드가 생성한 작은 실행 스크립트를 업로드 저장소에 기록한다.
     * 사용자 입력 파일명은 기존 업로드와 동일하게 정리하며 루트 밖 경로는 허용하지 않는다.
     */
    public String storeBytes(String originalFilename, byte[] content) {
        try {
            if (content == null || content.length == 0) {
                throw new RuntimeException("Failed to store empty content.");
            }
            String safeFilename = Paths.get(originalFilename == null ? "generated.ps1" : originalFilename)
                    .getFileName().toString()
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = UUID.randomUUID() + "_" + safeFilename;
            Path target = rootLocation.resolve(filename).normalize();
            if (!target.startsWith(rootLocation)) {
                throw new RuntimeException("Invalid file path: " + filename);
            }
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store generated content.", e);
        }
    }

    /**
     * 저장된 파일을 {@link Resource}로 로드하여 반환한다.
     *
     * <p>Path Traversal 방어: resolve 후 정규화된 경로가 rootLocation 하위인지 검증한다.
     * ({@code ../} 등으로 루트 밖 접근 시도를 차단)
     *
     * @param filename 조회할 파일명
     * @return 읽기 가능한 {@link Resource}
     * @throws RuntimeException 접근 거부, 파일 미존재, 또는 잘못된 URL인 경우
     */
    public Resource loadAsResource(String filename) {
        try {
            // Path Traversal 방어
            Path file = rootLocation.resolve(filename).normalize();
            if (!file.startsWith(rootLocation)) {
                throw new RuntimeException("Access denied: " + filename);
            }
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new RuntimeException("Could not read file: " + filename);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Could not read file: " + filename, e);
        }
    }
}
