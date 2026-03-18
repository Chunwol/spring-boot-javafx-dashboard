package com.example.backend.controller.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DashboardWidgetApiController {

    private final DashboardWidgetItemRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashboardWidgetApiController(DashboardWidgetItemRepository repository) {
        this.repository = repository;
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
        return repository.findAll().stream()
                .filter(item -> "TEXT".equalsIgnoreCase(item.getType()))
                .map(this::toTextMap)
                .toList();
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
        return repository.findAll().stream()
                .filter(item -> "IMAGE".equalsIgnoreCase(item.getType()))
                .map(this::toImageMap)
                .toList();
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
