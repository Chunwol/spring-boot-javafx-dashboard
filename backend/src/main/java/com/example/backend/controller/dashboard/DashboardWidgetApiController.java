package com.example.backend.controller.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.example.backend.service.storage.FileStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DashboardWidgetApiController {

    private static final Logger log = LoggerFactory.getLogger(DashboardWidgetApiController.class);

    private final DashboardWidgetItemRepository repository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${SERVER_BASE_URL:http://localhost:8080}")
    private String serverBaseUrl;

    public DashboardWidgetApiController(DashboardWidgetItemRepository repository,
                                        FileStorageService fileStorageService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    // ---- 레거시/신규 공통 ----

    @GetMapping("/dashboard/widgets")
    public ResponseEntity<List<DashboardWidgetItem>> getAllWidgets() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping("/dashboard/widgets")
    public ResponseEntity<DashboardWidgetItem> createWidget(@RequestBody DashboardWidgetItem item) {
        return ResponseEntity.status(201).body(repository.save(item));
    }

    @PutMapping("/dashboard/widgets/{id}")
    public ResponseEntity<DashboardWidgetItem> updateWidget(@PathVariable Long id, @RequestBody DashboardWidgetItem details) {
        return repository.findById(id).map(item -> {
            if (details.getX() != null) item.setX(details.getX());
            if (details.getY() != null) item.setY(details.getY());
            if (details.getWidth() != null) item.setWidth(details.getWidth());
            if (details.getHeight() != null) item.setHeight(details.getHeight());
            item.setVisible(details.isVisible());
            return ResponseEntity.ok(repository.save(item));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/dashboard/widgets/{id}")
    public ResponseEntity<Void> deleteWidget(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---- TEXT API (/api/v1/dashboard/texts) ----

    @GetMapping("/dashboard/texts")
    public List<Map<String, Object>> listTextItems() {
        try {
            return repository.findByTypeIgnoreCase("TEXT").stream()
                    .map(this::toTextMap)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list text dashboard widgets", e);
            return List.of();
        }
    }

    @PostMapping("/dashboard/texts")
    public ResponseEntity<Map<String, Object>> createTextItem(@RequestBody Map<String, Object> req) {
        DashboardWidgetItem item = new DashboardWidgetItem();
        item.setType("TEXT");
        applyCommon(item, req, 50.0, 50.0, 200.0, 120.0, true);
        item.setConfig(writeConfig(req));
        DashboardWidgetItem saved = repository.save(item);
        return ResponseEntity.status(201).body(toTextMap(saved));
    }

    @PutMapping("/dashboard/texts/{id}")
    public ResponseEntity<Map<String, Object>> updateTextItem(@PathVariable Long id,
                                                               @RequestBody Map<String, Object> req) {
        return repository.findById(id).map(item -> {
            item.setType("TEXT");
            applyCommon(item, req, item.getX(), item.getY(), item.getWidth(), item.getHeight(), item.isVisible());

            Map<String, Object> current = readConfig(item.getConfig());
            current.putAll(req);
            item.setConfig(writeConfig(current));
            DashboardWidgetItem saved = repository.save(item);
            return ResponseEntity.ok(toTextMap(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/dashboard/texts/{id}")
    public ResponseEntity<Void> deleteTextItem(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dashboard/texts/restore")
    public ResponseEntity<Map<String, Object>> restoreTextItems(@RequestBody List<Map<String, Object>> items) {
        List<Long> createdIds = new ArrayList<>();
        for (Map<String, Object> req : items) {
            DashboardWidgetItem item = new DashboardWidgetItem();
            item.setType("TEXT");
            applyCommon(item, req, 50.0, 50.0, 200.0, 120.0, true);
            item.setConfig(writeConfig(req));
            createdIds.add(repository.save(item).getId());
        }
        return ResponseEntity.ok(Map.of("restored", createdIds.size(), "ids", createdIds));
    }

    // ---- IMAGE API (/api/v1/dashboard/images) ----

    @GetMapping("/dashboard/images")
    public List<Map<String, Object>> listImageItems() {
        try {
            return repository.findByTypeIgnoreCase("IMAGE").stream()
                    .map(this::toImageMap)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list image dashboard widgets", e);
            return List.of();
        }
    }

    @PostMapping("/dashboard/images")
    public ResponseEntity<Map<String, Object>> createImageItem(@RequestBody Map<String, Object> req) {
        DashboardWidgetItem item = new DashboardWidgetItem();
        item.setType("IMAGE");
        applyCommon(item, req, 100.0, 100.0, 300.0, 200.0, true);
        item.setConfig(writeConfig(req));
        DashboardWidgetItem saved = repository.save(item);
        return ResponseEntity.status(201).body(toImageMap(saved));
    }

    @PutMapping("/dashboard/images/{id}")
    public ResponseEntity<Map<String, Object>> updateImageItem(@PathVariable Long id,
                                                                @RequestBody Map<String, Object> req) {
        return repository.findById(id).map(item -> {
            item.setType("IMAGE");
            applyCommon(item, req, item.getX(), item.getY(), item.getWidth(), item.getHeight(), item.isVisible());

            Map<String, Object> current = readConfig(item.getConfig());
            current.putAll(req);
            item.setConfig(writeConfig(current));
            DashboardWidgetItem saved = repository.save(item);
            return ResponseEntity.ok(toImageMap(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/dashboard/images/{id}")
    public ResponseEntity<Void> deleteImageItem(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/dashboard/images/upload
     * 이미지 파일을 업로드하고 IMAGE 위젯으로 등록한다.
     */
    @PostMapping(value = "/dashboard/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadImageFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "100") double x,
            @RequestParam(defaultValue = "100") double y,
            @RequestParam(defaultValue = "360") double width,
            @RequestParam(defaultValue = "240") double height) {
        try {
            String filename = fileStorageService.store(file);
            String imageUrl = serverBaseUrl + "/api/v1/files/" + filename;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("imageUrl", imageUrl);
            req.put("x", x);
            req.put("y", y);
            req.put("width", width);
            req.put("height", height);
            req.put("visible", true);
            DashboardWidgetItem item = new DashboardWidgetItem();
            item.setType("IMAGE");
            applyCommon(item, req, 100.0, 100.0, 360.0, 240.0, true);
            item.setConfig(writeConfig(req));
            DashboardWidgetItem saved = repository.save(item);
            return ResponseEntity.status(201).body(toImageMap(saved));
        } catch (Exception e) {
            log.error("Image upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/files/{filename}
     * 업로드된 파일을 서빙한다.
     */
    @GetMapping("/files/{filename}")
    public ResponseEntity<Resource> serveFile(@PathVariable String filename,
                                              HttpServletRequest request) {
        // Path traversal 방지: filename에 경로 구분자 불허
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Resource resource = fileStorageService.loadAsResource(filename);
            String contentType;
            try {
                contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
            } catch (IOException e) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (Exception e) {
            log.warn("File not found: {}", filename);
            return ResponseEntity.notFound().build();
        }
    }

    // ---- helper ----

    private void applyCommon(DashboardWidgetItem item, Map<String, Object> req,
                             Double dx, Double dy, Double dw, Double dh, boolean dvis) {
        item.setGroupId(asLong(req.getOrDefault("groupId", item.getGroupId() == null ? 1L : item.getGroupId())));
        item.setX(asDouble(req.getOrDefault("x", dx == null ? 0.0 : dx)));
        item.setY(asDouble(req.getOrDefault("y", dy == null ? 0.0 : dy)));
        item.setWidth(asDouble(req.getOrDefault("width", dw == null ? 200.0 : dw)));
        item.setHeight(asDouble(req.getOrDefault("height", dh == null ? 120.0 : dh)));
        item.setVisible(asBoolean(req.getOrDefault("visible", dvis)));
    }

    private Map<String, Object> toTextMap(DashboardWidgetItem item) {
        Map<String, Object> cfg = readConfig(item.getConfig());
        Map<String, Object> out = baseMap(item);
        out.put("text", cfg.getOrDefault("text", ""));
        out.put("config", item.getConfig());
        return out;
    }

    private Map<String, Object> toImageMap(DashboardWidgetItem item) {
        Map<String, Object> cfg = readConfig(item.getConfig());
        Map<String, Object> out = baseMap(item);
        out.put("imageUrl", cfg.getOrDefault("imageUrl", ""));
        out.put("config", item.getConfig());
        return out;
    }

    private Map<String, Object> baseMap(DashboardWidgetItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.getId());
        out.put("groupId", item.getGroupId());
        out.put("type", item.getType());
        out.put("x", item.getX());
        out.put("y", item.getY());
        out.put("width", item.getWidth());
        out.put("height", item.getHeight());
        out.put("visible", item.isVisible());
        return out;
    }

    private Map<String, Object> readConfig(String raw) {
        try {
            if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeConfig(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Double asDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private static Long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static boolean asBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
