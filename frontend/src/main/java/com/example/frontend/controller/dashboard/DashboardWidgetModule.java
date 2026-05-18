package com.example.frontend.controller.dashboard;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 대시보드 위젯(텍스트/이미지 아이템) CRUD 및 UI 렌더링 모듈.
 */
public class DashboardWidgetModule {
    private static final Logger log = LoggerFactory.getLogger(DashboardWidgetModule.class);
    /**
     * 텍스트 위젯 이름 인라인 변경 (id, 새 이름, 완료 콜백)
     */
    public void renameTextItem(Long id, String newName, Runnable onComplete) {
        if (!ensureWritePermission("텍스트 이름 변경")) {
            return;
        }
        if (id == null || newName == null || newName.isBlank()) return;
        long dashboardId = dashboardIdSupplier.getAsLong();
        String path = "/dashboard/texts/" + id + "/rename";
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", newName);
        if (dashboardId > 0) payload.put("dashboardId", dashboardId);
        apiClient.putJson(path, payload)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadTextItems();
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "이름 변경 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                    return null;
                });
    }

    private final ApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Pane classroomContainer;
    private final Map<Long, VBox> textItemMap;
    private final Map<Long, VBox> imageItemMap;
    private final BooleanSupplier editModeSupplier;
    private final BooleanSupplier snapModeSupplier;
    private final Runnable onRefreshPanelList;
    private LongSupplier dashboardIdSupplier = () -> -1L;
    private final DashboardInteractionService dashboardInteractionService = new DashboardInteractionService();
    private static final double IMAGE_CONTROL_HEIGHT = 34.0;

    // 삭제 버튼 추적 (editMode 연동)
    private final Map<Long, Button> textDeleteBtns = new java.util.HashMap<>();
    private final Map<Long, Button> imageDeleteBtns = new java.util.HashMap<>();
    private final Map<Long, HBox> textHeaders = new java.util.HashMap<>();
    private final Map<Long, VBox> textResizeHandles = new java.util.HashMap<>();
    private final Map<Long, TextArea> textEditors = new java.util.HashMap<>();
    private final Map<Long, Label> textViews = new java.util.HashMap<>();
    private final Map<Long, VBox> imageResizeHandles = new java.util.HashMap<>();

    private List<Map<String, Object>> cachedTexts = new ArrayList<>();
    private List<Map<String, Object>> cachedImages = new ArrayList<>();

    public DashboardWidgetModule(
            ObjectMapper objectMapper,
            String apiBaseUrl,
            Pane classroomContainer,
            Map<Long, VBox> textItemMap,
            Map<Long, VBox> imageItemMap,
            BooleanSupplier editModeSupplier,
            BooleanSupplier snapModeSupplier,
            Runnable onRefreshPanelList) {
        this.apiClient = ApiClient.getInstance();
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.classroomContainer = classroomContainer;
        this.textItemMap = textItemMap;
        this.imageItemMap = imageItemMap;
        this.editModeSupplier = editModeSupplier;
        this.snapModeSupplier = snapModeSupplier;
        this.onRefreshPanelList = onRefreshPanelList;
    }

    public void setDashboardIdSupplier(LongSupplier supplier) {
        this.dashboardIdSupplier = supplier != null ? supplier : () -> -1L;
    }

    private boolean ensureWritePermission(String actionName) {
        if (!apiClient.isViewerRole()) {
            return true;
        }
        Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                ApiClient.viewerWriteDeniedMessage(actionName)).show());
        return false;
    }

    private String resolveErrorMessage(Throwable ex, String fallback) {
        return ApiClient.userFacingErrorMessage(ex, fallback);
    }

    public List<Map<String, Object>> getCachedTexts() { return cachedTexts; }
    public List<Map<String, Object>> getCachedImages() { return cachedImages; }

    /** editMode 연동: 삭제 버튼 visibility 토글 */
    public void applyEditMode(boolean editMode) {
        textDeleteBtns.values().forEach(btn -> {
            btn.setVisible(editMode);
            btn.setManaged(editMode);
        });
        textHeaders.values().forEach(header -> {
            header.setVisible(editMode);
            header.setManaged(editMode);
        });
        textResizeHandles.values().forEach(handle -> {
            handle.setVisible(editMode);
            handle.setManaged(editMode);
        });
        textEditors.values().forEach(editor -> {
            editor.setEditable(editMode);
            editor.setMouseTransparent(!editMode);
            editor.setFocusTraversable(editMode);
            editor.setVisible(editMode);
            editor.setManaged(editMode);
            Object rawColor = editor.getProperties().getOrDefault("fontColor", "#111111");
            String fontColor = rawColor == null ? "#111111" : rawColor.toString();
            applyTextStyle(editor, fontColor, editMode);
        });
        textViews.values().forEach(view -> {
            view.setVisible(!editMode);
            view.setManaged(!editMode);
        });
        textItemMap.values().forEach(box -> {
            box.getStyleClass().removeAll("widget-item-text-edit", "widget-item-view");
            box.getStyleClass().add(editMode ? "widget-item-text-edit" : "widget-item-view");
        });
        imageDeleteBtns.values().forEach(btn -> {
            btn.setVisible(editMode);
            btn.setManaged(editMode);
        });
        imageResizeHandles.values().forEach(handle -> {
            handle.setVisible(editMode);
            handle.setManaged(editMode);
        });
        imageItemMap.values().forEach(box -> {
            ImageView iv = findImageView(box);
            if (iv != null) {
                box.setPrefSize(iv.getFitWidth(), iv.getFitHeight() + (editMode ? IMAGE_CONTROL_HEIGHT : 0));
            }
                box.getStyleClass().removeAll("widget-item-image-edit", "widget-item-view");
                box.getStyleClass().add(editMode ? "widget-item-image-edit" : "widget-item-view");
        });
    }

    public void applySnapshot(List<Map<String, Object>> texts, List<Map<String, Object>> images) {
        cachedTexts = texts == null ? List.of() : texts;
        cachedImages = images == null ? List.of() : images;
        Platform.runLater(() -> {
            updateTextItemsUi(cachedTexts);
            updateImageItemsUi(cachedImages);
        });
    }

    // ─────────── 텍스트 아이템 ───────────

    public void handleAddTextItem() {
        if (!ensureWritePermission("텍스트 패널 추가")) {
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("텍스트 위젯 추가");
        dialog.setHeaderText("표시할 텍스트를 입력하세요");

        TextArea input;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/widget-text-add-dialog-content.fxml"));
            input = loader.load();
        } catch (IOException e) {
            log.warn("Failed to load text widget dialog", e);
            new Alert(Alert.AlertType.ERROR, "다이얼로그 로드 실패: " + e.getMessage()).showAndWait();
            return;
        }
        dialog.getDialogPane().setContent(input);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ValidationSupport validationSupport = new ValidationSupport();
        validationSupport.registerValidator(
            input,
            Validator.createPredicateValidator(
                (Object value) -> {
                    if (value instanceof TextArea textArea) {
                        return textArea.getText() != null && !textArea.getText().trim().isEmpty();
                    }
                    return false;
                },
                "텍스트 내용을 입력하세요."
            )
        );
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.disableProperty().bind(Bindings.createBooleanBinding(
                validationSupport::isInvalid,
                validationSupport.invalidProperty()
            ));
        }

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return;
        }
        String text = input.getText() == null ? "" : input.getText().trim();
        if (text.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "텍스트 내용을 입력하세요.").showAndWait();
            return;
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("text", text);
        payload.put("x", 50);
        payload.put("y", 50);
        payload.put("width", 280);
        payload.put("height", 180);
        payload.put("fontSize", 14);
        payload.put("bold", false);
        payload.put("italic", false);
        payload.put("fontColor", "#000000");
        payload.put("backgroundColor", "#ffffff");
        payload.put("visible", true);
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId > 0) payload.put("dashboardId", dashboardId);
        apiClient.postJson("/dashboard/texts", payload)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadTextItems();
                    new Alert(Alert.AlertType.INFORMATION, "텍스트 패널이 추가되었습니다.").showAndWait();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "텍스트 패널 추가 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                    return null;
                });
    }

    public void loadTextItems() {
        long dashboardId = dashboardIdSupplier.getAsLong();
        String path = dashboardId > 0
                ? "/dashboard/texts?dashboardId=" + dashboardId
                : "/dashboard/texts";
        apiClient.getJson(path, new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(items -> {
                    cachedTexts = items;
                    Platform.runLater(() -> updateTextItemsUi(items));
                })
                .exceptionally(ex -> {
                    cachedTexts = List.of();
                    Platform.runLater(() -> updateTextItemsUi(List.of()));
                    return null;
                });
    }

    private void updateTextItemsUi(List<Map<String, Object>> items) {
        onRefreshPanelList.run();

        List<Long> visibleIds = items.stream()
                .filter(m -> !Boolean.FALSE.equals(m.getOrDefault("visible", true)))
                .filter(m -> m.get("id") instanceof Number)
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();

        textItemMap.keySet().removeIf(id -> {
            if (!visibleIds.contains(id)) {
                classroomContainer.getChildren().remove(textItemMap.get(id));
                textDeleteBtns.remove(id);
                textHeaders.remove(id);
                textResizeHandles.remove(id);
                textEditors.remove(id);
                textViews.remove(id);
                return true;
            }
            return false;
        });

        for (Map<String, Object> item : items) {
            if (Boolean.FALSE.equals(item.getOrDefault("visible", true))) continue;
            if (!(item.get("id") instanceof Number idNum)) continue;
            Long id = idNum.longValue();
            VBox box = textItemMap.get(id);

            Object xObj = item.get("x");
            double x = xObj instanceof Number ? ((Number) xObj).doubleValue() : 50.0;
            Object yObj = item.get("y");
            double y = yObj instanceof Number ? ((Number) yObj).doubleValue() : 50.0;
            Object wObj = item.get("width");
            double w = wObj instanceof Number ? ((Number) wObj).doubleValue() : 220.0;
            Object hObj = item.get("height");
            double h = hObj instanceof Number ? ((Number) hObj).doubleValue() : 120.0;
            String text = "새 텍스트";
            Object fontSizeObj = item.get("fontSize");
            int fontSize = fontSizeObj instanceof Number ? ((Number) fontSizeObj).intValue() : 14;
                String fontColor = "#111111";
            boolean bold = Boolean.TRUE.equals(item.get("bold"))
                    || "BOLD".equalsIgnoreCase(String.valueOf(item.getOrDefault("fontWeight", "")));
            boolean italic = Boolean.TRUE.equals(item.get("italic"))
                    || "ITALIC".equalsIgnoreCase(String.valueOf(item.getOrDefault("fontStyle", "")));
            try {
                if (item.containsKey("config") && item.get("config") != null) {
                    String cfg = item.get("config").toString();
                    if (!cfg.isBlank()) {
                        Map<String, Object> parsed = objectMapper.readValue(cfg, new TypeReference<Map<String, Object>>() {});
                        if (parsed.containsKey("text")) text = parsed.get("text").toString();
                        if (parsed.containsKey("fontSize") && parsed.get("fontSize") instanceof Number n) fontSize = n.intValue();
                        if (parsed.containsKey("bold")) bold = Boolean.TRUE.equals(parsed.get("bold"));
                        if (parsed.containsKey("italic")) italic = Boolean.TRUE.equals(parsed.get("italic"));
                        if (parsed.containsKey("fontColor") && parsed.get("fontColor") != null) fontColor = parsed.get("fontColor").toString();
                    }
                }
                if (item.containsKey("text")) text = item.get("text").toString();
            } catch (Exception e) {
                // ignore parse errors
            }

            if (box == null) {
                box = new VBox();
                HBox header;
                Button deleteBtn;
                Button boldBtn;
                Button italicBtn;
                Button sizeDownBtn;
                Button sizeUpBtn;
                ColorPicker colorPicker;
                try {
                    FXMLLoader headerLoader = new FXMLLoader(getClass().getResource("/fxml/widget-text-header.fxml"));
                    header      = headerLoader.load();
                    deleteBtn   = (Button)      headerLoader.getNamespace().get("deleteBtn");
                    boldBtn     = (Button)      headerLoader.getNamespace().get("boldBtn");
                    italicBtn   = (Button)      headerLoader.getNamespace().get("italicBtn");
                    sizeDownBtn = (Button)      headerLoader.getNamespace().get("sizeDownBtn");
                    sizeUpBtn   = (Button)      headerLoader.getNamespace().get("sizeUpBtn");
                    colorPicker = (ColorPicker) headerLoader.getNamespace().get("colorPicker");
                    colorPicker.setValue(parseColorSafe(fontColor));
                } catch (Exception headerEx) {
                    throw new IllegalStateException("Failed to load widget-text-header.fxml", headerEx);
                }
                deleteBtn.setVisible(editModeSupplier.getAsBoolean());
                deleteBtn.setManaged(editModeSupplier.getAsBoolean());
                deleteBtn.setOnAction(e -> handleDeleteTextItem(id));
                textDeleteBtns.put(id, deleteBtn);
                header.setVisible(editModeSupplier.getAsBoolean());
                header.setManaged(editModeSupplier.getAsBoolean());
                textHeaders.put(id, header);

                TextArea textArea = new TextArea(text);
                textArea.setWrapText(true);
                textArea.getProperties().put("fontSize", fontSize);
                textArea.getProperties().put("bold", bold);
                textArea.getProperties().put("italic", italic);
                textArea.getProperties().put("fontColor", fontColor);
                textArea.setEditable(editModeSupplier.getAsBoolean());
                textArea.setMouseTransparent(!editModeSupplier.getAsBoolean());
                textArea.setFocusTraversable(editModeSupplier.getAsBoolean());
                textArea.setVisible(editModeSupplier.getAsBoolean());
                textArea.setManaged(editModeSupplier.getAsBoolean());
                applyTextStyle(textArea, fontColor, editModeSupplier.getAsBoolean());
                textArea.setPrefWidth(w);
                textArea.setPrefHeight(Math.max(80, h - 64));
                textEditors.put(id, textArea);

                Label textView = new Label(text);
                textView.setWrapText(true);
                textView.setVisible(!editModeSupplier.getAsBoolean());
                textView.setManaged(!editModeSupplier.getAsBoolean());
                textView.setPrefWidth(w);
                textViews.put(id, textView);
                applyTextViewStyle(textView, fontSize, bold, italic, fontColor);
                final VBox targetBox = box;

                boldBtn.setOnAction(e -> {
                    boolean next = !Boolean.TRUE.equals(textArea.getProperties().get("bold"));
                    textArea.getProperties().put("bold", next);
                    applyTextStyle(textArea, String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")), true);
                    applyTextViewStyle(textView,
                            ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue(),
                            Boolean.TRUE.equals(textArea.getProperties().get("bold")),
                            Boolean.TRUE.equals(textArea.getProperties().get("italic")),
                            String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")));
                    updateTextItem(id, textArea.getText(), targetBox.getLayoutX(), targetBox.getLayoutY(), targetBox.getPrefWidth(), targetBox.getPrefHeight());
                });
                italicBtn.setOnAction(e -> {
                    boolean next = !Boolean.TRUE.equals(textArea.getProperties().get("italic"));
                    textArea.getProperties().put("italic", next);
                    applyTextStyle(textArea, String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")), true);
                    applyTextViewStyle(textView,
                            ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue(),
                            Boolean.TRUE.equals(textArea.getProperties().get("bold")),
                            Boolean.TRUE.equals(textArea.getProperties().get("italic")),
                            String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")));
                    updateTextItem(id, textArea.getText(), targetBox.getLayoutX(), targetBox.getLayoutY(), targetBox.getPrefWidth(), targetBox.getPrefHeight());
                });
                sizeDownBtn.setOnAction(e -> {
                    int current = (int) textArea.getProperties().getOrDefault("fontSize", 14);
                    int next = Math.max(10, current - 1);
                    textArea.getProperties().put("fontSize", next);
                    applyTextStyle(textArea, String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")), true);
                    applyTextViewStyle(textView,
                            ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue(),
                            Boolean.TRUE.equals(textArea.getProperties().get("bold")),
                            Boolean.TRUE.equals(textArea.getProperties().get("italic")),
                            String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")));
                    updateTextItem(id, textArea.getText(), targetBox.getLayoutX(), targetBox.getLayoutY(), targetBox.getPrefWidth(), targetBox.getPrefHeight());
                });
                sizeUpBtn.setOnAction(e -> {
                    int current = (int) textArea.getProperties().getOrDefault("fontSize", 14);
                    int next = Math.min(48, current + 1);
                    textArea.getProperties().put("fontSize", next);
                    applyTextStyle(textArea, String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")), true);
                    applyTextViewStyle(textView,
                            ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue(),
                            Boolean.TRUE.equals(textArea.getProperties().get("bold")),
                            Boolean.TRUE.equals(textArea.getProperties().get("italic")),
                            String.valueOf(textArea.getProperties().getOrDefault("fontColor", "#111111")));
                    updateTextItem(id, textArea.getText(), targetBox.getLayoutX(), targetBox.getLayoutY(), targetBox.getPrefWidth(), targetBox.getPrefHeight());
                });
                final ColorPicker colorPickerFinal = colorPicker;
                colorPickerFinal.setOnAction(e -> {
                    String nextColor = toHexColor(colorPickerFinal.getValue());
                    textArea.getProperties().put("fontColor", nextColor);
                    applyTextStyle(textArea, nextColor, true);
                    applyTextViewStyle(textView,
                            ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue(),
                            Boolean.TRUE.equals(textArea.getProperties().get("bold")),
                            Boolean.TRUE.equals(textArea.getProperties().get("italic")),
                            nextColor);
                    updateTextItem(id, textArea.getText(), targetBox.getLayoutX(), targetBox.getLayoutY(), targetBox.getPrefWidth(), targetBox.getPrefHeight());
                });

                textArea.textProperty().addListener((obs, oldVal, newVal) -> textView.setText(newVal));

                box.getChildren().addAll(header, textArea, textView);
                box.setPrefSize(w, h);
                box.getStyleClass().add(editModeSupplier.getAsBoolean() ? "widget-item-text-edit" : "widget-item-view");
                makeTextItemInteractive(box, textArea, id);
                VBox resizeHandleWrap = addTextResizeHandle(box, textArea, id);
                textResizeHandles.put(id, resizeHandleWrap);
                textItemMap.put(id, box);
                classroomContainer.getChildren().add(box);
                box.toFront();
            } else {
                TextArea textArea = textEditors.get(id);
                Label textView = textViews.get(id);
                if (textArea == null || textView == null) continue;
                textArea.setText(text);
                textArea.getProperties().put("fontSize", fontSize);
                textArea.getProperties().put("bold", bold);
                textArea.getProperties().put("italic", italic);
                textArea.getProperties().put("fontColor", fontColor);
                applyTextStyle(textArea, fontColor, editModeSupplier.getAsBoolean());
                applyTextViewStyle(textView, fontSize, bold, italic, fontColor);
                textView.setText(text);
                textView.setPrefWidth(w);
                textArea.setPrefWidth(w);
                textArea.setPrefHeight(Math.max(80, h - 64));
                box.setPrefSize(w, h);
            }
            box.setLayoutX(x);
            box.setLayoutY(y);
        }
    }

    private void makeTextItemInteractive(VBox box, TextArea textArea, Long id) {
        dashboardInteractionService.attachDragHandlers(
                box,
                editModeSupplier,
                this::applySnap,
                () -> updateTextItem(
                        id,
                        textArea.getText(),
                        box.getLayoutX(),
                        box.getLayoutY(),
                        box.getPrefWidth(),
                        box.getPrefHeight())
        );

        textArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal && editModeSupplier.getAsBoolean()) {
                updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
            }
        });
    }

    public void updateTextItemPosition(Long id, double x, double y, double w, double h, boolean visible) {
        if (!ensureWritePermission("텍스트 패널 위치 변경")) {
            return;
        }
        apiClient.putJson("/dashboard/texts/" + id, Map.of(
            "x", x,
            "y", y,
            "width", w,
            "height", h,
            "visible", visible
        )).exceptionally(ex -> {
            log.warn("Failed to update text position: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
            return null;
        });
    }

    private void updateTextItem(Long id, String text, double x, double y, double w, double h) {
        if (!ensureWritePermission("텍스트 패널 수정")) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("text", text);
        payload.put("x", x);
        payload.put("y", y);
        payload.put("width", w);
        payload.put("height", h);

        VBox box = textItemMap.get(id);
        if (box != null && box.getChildren().size() > 1 && box.getChildren().get(1) instanceof TextArea ta) {
            payload.put("fontSize", ta.getProperties().getOrDefault("fontSize", 14));
            payload.put("bold", ta.getProperties().getOrDefault("bold", false));
            payload.put("italic", ta.getProperties().getOrDefault("italic", false));
            payload.put("fontColor", ta.getProperties().getOrDefault("fontColor", "#111111"));
        }

        apiClient.putJson("/dashboard/texts/" + id, payload).exceptionally(ex -> {
            log.warn("Failed to update text widget: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
            return null;
        });
    }

    private void applyTextStyle(TextArea textArea, String fontColor, boolean editMode) {
        int fontSize = ((Number) textArea.getProperties().getOrDefault("fontSize", 14)).intValue();
        boolean bold = Boolean.TRUE.equals(textArea.getProperties().get("bold"));
        boolean italic = Boolean.TRUE.equals(textArea.getProperties().get("italic"));
        textArea.setFont(Font.font("Malgun Gothic",
                bold ? FontWeight.BOLD : FontWeight.NORMAL,
                italic ? FontPosture.ITALIC : FontPosture.REGULAR,
                fontSize));
        textArea.setStyle("-fx-text-fill: " + fontColor + ";");
        textArea.getStyleClass().removeAll("widget-textarea-edit", "widget-textarea-view");
        textArea.getStyleClass().add(editMode ? "widget-textarea-edit" : "widget-textarea-view");
    }

    private void applyTextViewStyle(Label label, int fontSize, boolean bold, boolean italic, String fontColor) {
        label.setFont(Font.font("Malgun Gothic",
                bold ? FontWeight.BOLD : FontWeight.NORMAL,
                italic ? FontPosture.ITALIC : FontPosture.REGULAR,
                fontSize));
        label.setTextFill(parseColorSafe(fontColor));
    }

    private Color parseColorSafe(String raw) {
        try {
            return Color.web(raw == null || raw.isBlank() ? "#111111" : raw);
        } catch (Exception e) {
            return Color.web("#111111");
        }
    }

    private String toHexColor(Color color) {
        if (color == null) return "#111111";
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    public void handleDeleteTextItem(Long id) {
        if (!ensureWritePermission("텍스트 패널 삭제")) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "텍스트를 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
            apiClient.delete("/dashboard/texts/" + id)
                        .thenAccept(resp -> Platform.runLater(this::loadTextItems))
                        .exceptionally(ex -> {
                            log.warn("텍스트 삭제 실패: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "텍스트 삭제 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                            return null;
                        });
            }
        });
    }

    public void toggleTextVisibility(Long id, Map<String, Object> map, boolean isVisible) {
        if (!ensureWritePermission("텍스트 표시 변경")) {
            return;
        }
        apiClient.putJson("/dashboard/texts/" + id, Map.of("visible", isVisible)).exceptionally(ex -> {
            log.warn("Failed to toggle text widget visibility: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
            return null;
        });
    }

    // ─────────── 이미지 아이템 ───────────

    public void handleAddImageItem() {
        if (!ensureWritePermission("이미지 패널 추가")) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("이미지 파일 선택");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("이미지 파일", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("모든 파일", "*.*")
        );
        File file = fileChooser.showOpenDialog(
                classroomContainer.getScene() != null ? classroomContainer.getScene().getWindow() : null
        );
        if (file == null) return;

        long dashboardId = dashboardIdSupplier.getAsLong();
        String uploadPath = dashboardId > 0
                ? "/dashboard/images/upload?dashboardId=" + dashboardId
                : "/dashboard/images/upload";
        apiClient.uploadImageFile(uploadPath, file)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadImageItems();
                    new Alert(Alert.AlertType.INFORMATION, "이미지 파일이 대시보드에 추가되었습니다.").showAndWait();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "이미지 업로드 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                    return null;
                });
    }

    public void loadImageItems() {
        long dashboardId = dashboardIdSupplier.getAsLong();
        String path = dashboardId > 0
                ? "/dashboard/images?dashboardId=" + dashboardId
                : "/dashboard/images";
        apiClient.getJson(path, new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(items -> {
                    cachedImages = items;
                    Platform.runLater(() -> updateImageItemsUi(items));
                })
                .exceptionally(ex -> {
                    cachedImages = List.of();
                    Platform.runLater(() -> updateImageItemsUi(List.of()));
                    return null;
                });
    }

    private void updateImageItemsUi(List<Map<String, Object>> items) {
        onRefreshPanelList.run();

        List<Long> visibleIds = items.stream()
                .filter(m -> !Boolean.FALSE.equals(m.getOrDefault("visible", true)))
                .filter(m -> m.get("id") instanceof Number)
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();

        imageItemMap.keySet().removeIf(id -> {
            if (!visibleIds.contains(id)) {
                classroomContainer.getChildren().remove(imageItemMap.get(id));
                imageDeleteBtns.remove(id);
                imageResizeHandles.remove(id);
                return true;
            }
            return false;
        });

        for (Map<String, Object> item : items) {
            if (Boolean.FALSE.equals(item.getOrDefault("visible", true))) continue;
            if (!(item.get("id") instanceof Number idNum)) continue;
            Long id = idNum.longValue();

            double x = item.getOrDefault("x", 80.0) instanceof Number ? ((Number) item.get("x")).doubleValue() : 80.0;
            double y = item.getOrDefault("y", 80.0) instanceof Number ? ((Number) item.get("y")).doubleValue() : 80.0;
            double w = item.getOrDefault("width", 320.0) instanceof Number ? ((Number) item.get("width")).doubleValue() : 320.0;
            double h = item.getOrDefault("height", 180.0) instanceof Number ? ((Number) item.get("height")).doubleValue() : 180.0;
            // (1) config JSON에서 imageUrl 추출 (loadImageItems() /dashboard/images 경로)
            String imageUrl = null;
            try {
                if (item.containsKey("config") && item.get("config") != null) {
                    String cfg = item.get("config").toString();
                    if (!cfg.isBlank()) {
                        Map<String, Object> parsed = objectMapper.readValue(cfg, new TypeReference<Map<String, Object>>() {});
                        if (parsed.containsKey("imageUrl")) imageUrl = parsed.get("imageUrl").toString();
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            // (2) top-level imageUrl 폴백 (applySnapshot() 15초 갱신 경로 — config 키 없음)
            if ((imageUrl == null || imageUrl.isBlank()) && item.containsKey("imageUrl")) {
                Object raw = item.get("imageUrl");
                if (raw != null && !raw.toString().isBlank()) imageUrl = raw.toString();
            }
            if (imageUrl == null || imageUrl.isBlank()) {
                imageUrl = apiBaseUrl.replace("/api/v1", "") + "/api/v1/files/placeholder.png";
            }
            final String imageUrlFinal = imageUrl;
            final double widthFinal = w;
            final double heightFinal = h;

            VBox box = imageItemMap.get(id);
            if (box == null) {
                VBox newBox;
                Button deleteBtn;
                try {
                    FXMLLoader imgLoader = new FXMLLoader(getClass().getResource("/fxml/widget-image-item.fxml"));
                    newBox    = imgLoader.load();
                    deleteBtn = (Button) imgLoader.getNamespace().get("deleteBtn");
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to load widget-image-item.fxml", ex);
                }
                deleteBtn.setVisible(editModeSupplier.getAsBoolean());
                deleteBtn.setManaged(editModeSupplier.getAsBoolean());
                deleteBtn.setOnAction(e -> handleDeleteImageItem(id));
                imageDeleteBtns.put(id, deleteBtn);
                ImageView imageView = new ImageView(new Image(imageUrlFinal, widthFinal, heightFinal, true, true, true));
                imageView.setFitWidth(widthFinal);
                imageView.setFitHeight(heightFinal);
                newBox.getChildren().add(imageView);
                newBox.setPrefSize(widthFinal, heightFinal + (editModeSupplier.getAsBoolean() ? IMAGE_CONTROL_HEIGHT : 0));
                newBox.getStyleClass().removeAll("widget-item-image-edit", "widget-item-view");
                newBox.getStyleClass().add(editModeSupplier.getAsBoolean() ? "widget-item-image-edit" : "widget-item-view");
                final VBox newBoxFinal = newBox;
                dashboardInteractionService.attachDragHandlers(
                        newBoxFinal,
                        editModeSupplier,
                        this::applySnap,
                        () -> apiClient.putJson("/dashboard/images/" + id, Map.of(
                                "x", newBoxFinal.getLayoutX(),
                                "y", newBoxFinal.getLayoutY(),
                                "width", imageView.getFitWidth(),
                                "height", imageView.getFitHeight(),
                                "visible", true,
                                "imageUrl", imageUrlFinal
                        )).exceptionally(ex -> {
                    log.warn("Failed to update image widget position", ex);
                    return null;
                })
                );
                imageItemMap.put(id, newBoxFinal);
                classroomContainer.getChildren().add(newBoxFinal);
                VBox resizeHandleWrap = addImageResizeHandle(newBoxFinal, id, imageUrlFinal);
                imageResizeHandles.put(id, resizeHandleWrap);
                newBoxFinal.toFront();
                box = newBoxFinal;
            } else {
                if (box.getChildren().size() > 1 && box.getChildren().get(1) instanceof ImageView iv) {
                    iv.setImage(new Image(imageUrlFinal, w, h, true, true, true));
                    iv.setFitWidth(w);
                    iv.setFitHeight(h);
                }
                box.setPrefSize(w, h + (editModeSupplier.getAsBoolean() ? IMAGE_CONTROL_HEIGHT : 0));
            }
            box.setLayoutX(x);
            box.setLayoutY(y);
        }
    }

    public void updateImageItemPosition(Long id, double x, double y, double w, double h, boolean visible) {
        if (!ensureWritePermission("이미지 패널 위치 변경")) {
            return;
        }
        apiClient.putJson("/dashboard/images/" + id, Map.of(
            "x", x,
            "y", y,
            "width", w,
            "height", h,
            "visible", visible
        )).exceptionally(ex -> {
            log.warn("Failed to update image position: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
            return null;
        });
    }

    public void handleDeleteImageItem(Long id) {
        if (!ensureWritePermission("이미지 패널 삭제")) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "이미지를 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
            apiClient.delete("/dashboard/images/" + id)
                        .thenAccept(resp -> Platform.runLater(this::loadImageItems))
                        .exceptionally(ex -> {
                            log.warn("이미지 삭제 실패: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "이미지 삭제 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                            return null;
                        });
            }
        });
    }

    public void toggleImageVisibility(Long id, Map<String, Object> map, boolean isVisible) {
        if (!ensureWritePermission("이미지 표시 변경")) {
            return;
        }
        apiClient.putJson("/dashboard/images/" + id, Map.of("visible", isVisible)).exceptionally(ex -> {
            log.warn("Failed to toggle image visibility: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
            return null;
        });
    }

    // ─────────── 백업 / 복원 ───────────

    public void handleBackupTextItems() {
        apiClient.get("/dashboard/texts")
                .thenAccept(body -> Platform.runLater(() -> {
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("텍스트 아이템 백업 저장");
                        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
                        fileChooser.setInitialFileName("dashboard_texts_backup_" + java.time.LocalDate.now() + ".json");
                        File file = fileChooser.showSaveDialog(classroomContainer.getScene().getWindow());

                        if (file != null) {
                            try {
                                Files.writeString(file.toPath(), body, StandardCharsets.UTF_8);
                                new Alert(Alert.AlertType.INFORMATION, "백업 완료").show();
                            } catch (IOException e) {
                                new Alert(Alert.AlertType.ERROR, "파일 저장 실패: " + e.getMessage()).show();
                            }
                        }
                }))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "네트워크 오류 또는 서버 응답 실패: " + cause.getMessage()).show());
                    return null;
                });
    }

    public void handleRestoreTextItems() {
        if (!ensureWritePermission("텍스트 백업 복원")) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("텍스트 아이템 백업 파일 열기");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fileChooser.showOpenDialog(classroomContainer.getScene().getWindow());

        if (file != null) {
            try {
                String jsonContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                restoreTextItems(jsonContent);
            } catch (IOException e) {
                new Alert(Alert.AlertType.ERROR, "파일 읽기 실패: " + e.getMessage()).show();
            } catch (Exception e) {
                new Alert(Alert.AlertType.ERROR, "복원 중 예외 발생: " + e.getMessage()).show();
            }
        }
    }

    private void restoreTextItems(String jsonContent) {
        apiClient.postJson("/dashboard/texts/restore", jsonContent)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadTextItems();
                    new Alert(Alert.AlertType.INFORMATION, "복원 완료").show();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "복원 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).show());
                    return null;
                });
    }

    private VBox addTextResizeHandle(VBox box, TextArea textArea, Long id) {
        VBox handleWrap = loadResizeHandleWrap();
        Label handle = (Label) handleWrap.getProperties().get("resizeHandleLabel");
        handleWrap.setVisible(editModeSupplier.getAsBoolean());
        handleWrap.setManaged(editModeSupplier.getAsBoolean());
        box.getChildren().add(handleWrap);

        dashboardInteractionService.attachResizeHandlers(
                box,
                handle,
                180,
                120,
                editModeSupplier,
                this::applySnap,
                (w, h) -> {
                    textArea.setPrefWidth(w);
                    textArea.setPrefHeight(Math.max(80, h - 64));
                },
                () -> updateTextItem(
                        id,
                        textArea.getText(),
                        box.getLayoutX(),
                        box.getLayoutY(),
                        box.getPrefWidth(),
                        box.getPrefHeight())
        );
        return handleWrap;
    }

    private VBox addImageResizeHandle(VBox box, Long id, String imageUrlFinal) {
        VBox handleWrap = loadResizeHandleWrap();
        Label handle = (Label) handleWrap.getProperties().get("resizeHandleLabel");
        handleWrap.setVisible(editModeSupplier.getAsBoolean());
        handleWrap.setManaged(editModeSupplier.getAsBoolean());
        box.getChildren().add(handleWrap);

        dashboardInteractionService.attachResizeHandlers(
                box,
                handle,
                220,
                180,
                editModeSupplier,
                this::applySnap,
                (w, h) -> {
                    // ImageView 크기만 업데이트 (매 픽셀 이동 시 new Image() 생성 제거 - 성능 개선)
                    if (box.getChildren().size() > 1 && box.getChildren().get(1) instanceof ImageView iv) {
                        iv.setFitWidth(w - 8);
                        iv.setFitHeight(Math.max(120, h - 64));
                        // 이미지 재로드는 최종 크기 저장 시에만 수행 (아래 로직에서 처리)
                    }
                },
                () -> apiClient.putJson("/dashboard/images/" + id, Map.of(
                        "x", box.getLayoutX(),
                        "y", box.getLayoutY(),
                        "width", getImagePersistWidth(box),
                        "height", getImagePersistHeight(box),
                        "visible", true,
                        "imageUrl", imageUrlFinal
                )).exceptionally(ex -> {
                    log.warn("Failed to apply resize handler", ex);
                    return null;
                })
        );
        return handleWrap;
    }

    private VBox loadResizeHandleWrap() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/widget-resize-handle.fxml"));
            VBox handleWrap = loader.load();
            Label handle = (Label) loader.getNamespace().get("handleLabel");
            handleWrap.getProperties().put("resizeHandleLabel", handle);
            return handleWrap;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load widget-resize-handle.fxml", e);
        }
    }

    private ImageView findImageView(VBox box) {
        if (box == null || box.getChildren().size() <= 1) {
            return null;
        }
        return box.getChildren().get(1) instanceof ImageView iv ? iv : null;
    }

    private double getImagePersistWidth(VBox box) {
        ImageView iv = findImageView(box);
        return iv != null ? iv.getFitWidth() : box.getPrefWidth();
    }

    private double getImagePersistHeight(VBox box) {
        ImageView iv = findImageView(box);
        return iv != null ? iv.getFitHeight() : box.getPrefHeight();
    }

    private double applySnap(double value) {
        if (snapModeSupplier == null || !snapModeSupplier.getAsBoolean()) {
            return value;
        }
        return Math.round(value / DashboardViewModel.GRID_SIZE) * DashboardViewModel.GRID_SIZE;
    }
}
