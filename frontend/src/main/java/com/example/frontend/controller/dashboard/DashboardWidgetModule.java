package com.example.frontend.controller.dashboard;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 대시보드 위젯(텍스트/이미지 아이템) CRUD 및 UI 렌더링 모듈.
 */
public class DashboardWidgetModule {

    private final ApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Pane classroomContainer;
    private final Map<Long, VBox> textItemMap;
    private final Map<Long, VBox> imageItemMap;
    private final BooleanSupplier editModeSupplier;
    private final BooleanSupplier snapModeSupplier;
    private final Runnable onRefreshPanelList;
    private static final double GRID_SIZE = 20.0;

    private List<Map<String, Object>> cachedTexts = new ArrayList<>();
    private List<Map<String, Object>> cachedImages = new ArrayList<>();

    public DashboardWidgetModule(
            HttpClient httpClient,
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

    public List<Map<String, Object>> getCachedTexts() { return cachedTexts; }
    public List<Map<String, Object>> getCachedImages() { return cachedImages; }

    // ─────────── 텍스트 아이템 ───────────

    public void handleAddTextItem() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("텍스트 위젯 추가");
        dialog.setHeaderText("표시할 텍스트를 입력하세요");
        TextArea input = new TextArea("공지사항을 입력하세요");
        input.setPrefRowCount(4);
        dialog.getDialogPane().setContent(input);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return;
        }
        String text = input.getText() == null ? "" : input.getText().trim();
        if (text.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "텍스트 내용을 입력하세요.").showAndWait();
            return;
        }

        Map<String, Object> payload = Map.of(
                "text", text,
                "x", 50,
                "y", 50,
                "width", 280,
                "height", 180,
                "fontSize", 14,
                "fontColor", "#000000",
                "backgroundColor", "#ffffff",
                "visible", true
        );
        apiClient.postJson("/dashboard/texts", payload)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadTextItems();
                    new Alert(Alert.AlertType.INFORMATION, "텍스트 패널이 추가되었습니다.").showAndWait();
                }))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "네트워크 오류 또는 서버 응답 실패: " + cause.getMessage()).showAndWait());
                    return null;
                });
    }

    public void loadTextItems() {
        apiClient.getJson("/dashboard/texts", new TypeReference<List<Map<String, Object>>>() {})
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
                .filter(m -> (boolean) m.getOrDefault("visible", true))
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();

        textItemMap.keySet().removeIf(id -> {
            if (!visibleIds.contains(id)) {
                classroomContainer.getChildren().remove(textItemMap.get(id));
                return true;
            }
            return false;
        });

        for (Map<String, Object> item : items) {
            if (!(boolean) item.getOrDefault("visible", true)) continue;
            Long id = ((Number) item.get("id")).longValue();
            VBox box = textItemMap.get(id);

            double x = item.getOrDefault("x", 50.0) instanceof Number ? ((Number) item.get("x")).doubleValue() : 50.0;
            double y = item.getOrDefault("y", 50.0) instanceof Number ? ((Number) item.get("y")).doubleValue() : 50.0;
            double w = item.getOrDefault("width", 220.0) instanceof Number ? ((Number) item.get("width")).doubleValue() : 220.0;
            double h = item.getOrDefault("height", 120.0) instanceof Number ? ((Number) item.get("height")).doubleValue() : 120.0;
            String text = "New Text";
            try {
                if (item.containsKey("config") && item.get("config") != null) {
                    String cfg = item.get("config").toString();
                    if (!cfg.isBlank()) {
                        Map<String, Object> parsed = objectMapper.readValue(cfg, new TypeReference<Map<String, Object>>() {});
                        if (parsed.containsKey("text")) text = parsed.get("text").toString();
                    }
                }
                if (item.containsKey("text")) text = item.get("text").toString();
            } catch (Exception e) {
                // ignore parse errors
            }

            if (box == null) {
                box = new VBox();
                Button deleteBtn = new Button("삭제");
                deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
                deleteBtn.setOnAction(e -> handleDeleteTextItem(id));
                VBox header = new VBox(deleteBtn);
                TextArea textArea = new TextArea(text);
                textArea.setWrapText(true);
                textArea.setPrefWidth(w);
                textArea.setPrefHeight(Math.max(80, h - 34));
                box.getChildren().addAll(header, textArea);
                box.setPrefSize(w, h);
                makeTextItemInteractive(box, textArea, id);
                addTextResizeHandle(box, textArea, id);
                textItemMap.put(id, box);
                classroomContainer.getChildren().add(box);
                box.toBack();
            } else {
                TextArea textArea = (TextArea) box.getChildren().get(1);
                textArea.setText(text);
                textArea.setPrefWidth(w);
                textArea.setPrefHeight(Math.max(80, h - 34));
                box.setPrefSize(w, h);
            }
            box.setLayoutX(x);
            box.setLayoutY(y);
        }
    }

    private void makeTextItemInteractive(VBox box, TextArea textArea, Long id) {
        final double[] dragStart = new double[2];

        box.setOnMousePressed(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            Point2D p = box.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
            dragStart[0] = p.getX() - box.getLayoutX();
            dragStart[1] = p.getY() - box.getLayoutY();
            box.toFront();
            e.consume();
        });

        box.setOnMouseDragged(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            Point2D p = box.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
            box.setLayoutX(applySnap(p.getX() - dragStart[0]));
            box.setLayoutY(applySnap(p.getY() - dragStart[1]));
            e.consume();
        });

        box.setOnMouseReleased(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
            e.consume();
        });

        textArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal && editModeSupplier.getAsBoolean()) {
                updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
            }
        });
    }

    public void updateTextItemPosition(Long id, double x, double y, double w, double h, boolean visible) {
        apiClient.putJson("/dashboard/texts/" + id, Map.of(
            "x", x,
            "y", y,
            "width", w,
            "height", h,
            "visible", visible
        )).exceptionally(ex -> null);
    }

    private void updateTextItem(Long id, String text, double x, double y, double w, double h) {
        apiClient.putJson("/dashboard/texts/" + id, Map.of(
            "text", text,
            "x", x,
            "y", y,
            "width", w,
            "height", h
        )).exceptionally(ex -> null);
    }

    public void handleDeleteTextItem(Long id) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "텍스트를 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
            apiClient.delete("/dashboard/texts/" + id)
                        .thenAccept(resp -> loadTextItems());
            }
        });
    }

    public void toggleTextVisibility(Long id, Map<String, Object> map, boolean isVisible) {
        String json = String.format("{\"visible\":%b}", isVisible);
        updateItemVisibility(id, json, "/dashboard/texts/");
    }

    // ─────────── 이미지 아이템 ───────────

    public void handleAddImageItem() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("이미지 위젯 추가");
        dialog.setHeaderText("이미지 URL을 입력하세요");
        TextField input = new TextField("https://via.placeholder.com/640x360.png?text=Dashboard");
        dialog.getDialogPane().setContent(input);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return;
        }
        String imageUrl = input.getText() == null ? "" : input.getText().trim();
        if (imageUrl.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "이미지 URL을 입력하세요.").showAndWait();
            return;
        }

        Map<String, Object> payload = Map.of(
                "imageUrl", imageUrl,
                "x", 100,
                "y", 100,
                "width", 360,
                "height", 240,
                "visible", true
        );
        apiClient.postJson("/dashboard/images", payload)
                .thenAccept(resp -> Platform.runLater(() -> {
                    loadImageItems();
                    new Alert(Alert.AlertType.INFORMATION, "이미지 패널이 추가되었습니다.").showAndWait();
                }))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "네트워크 오류 또는 서버 응답 실패: " + cause.getMessage()).showAndWait());
                    return null;
                });
    }

    public void loadImageItems() {
        apiClient.getJson("/dashboard/images", new TypeReference<List<Map<String, Object>>>() {})
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
                .filter(m -> (boolean) m.getOrDefault("visible", true))
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();

        imageItemMap.keySet().removeIf(id -> {
            if (!visibleIds.contains(id)) {
                classroomContainer.getChildren().remove(imageItemMap.get(id));
                return true;
            }
            return false;
        });

        for (Map<String, Object> item : items) {
            if (!(boolean) item.getOrDefault("visible", true)) continue;
            Long id = ((Number) item.get("id")).longValue();

            double x = item.getOrDefault("x", 80.0) instanceof Number ? ((Number) item.get("x")).doubleValue() : 80.0;
            double y = item.getOrDefault("y", 80.0) instanceof Number ? ((Number) item.get("y")).doubleValue() : 80.0;
            double w = item.getOrDefault("width", 320.0) instanceof Number ? ((Number) item.get("width")).doubleValue() : 320.0;
            double h = item.getOrDefault("height", 180.0) instanceof Number ? ((Number) item.get("height")).doubleValue() : 180.0;
            String imageUrl = "https://via.placeholder.com/320x180.png?text=Image";
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
            final String imageUrlFinal = imageUrl;
            final double widthFinal = w;
            final double heightFinal = h;

            VBox box = imageItemMap.get(id);
            if (box == null) {
                VBox newBox = new VBox(4);
                newBox.setStyle("-fx-border-color: #999; -fx-border-width: 1; -fx-background-color: #fff; -fx-padding: 4;");
                Button deleteBtn = new Button("삭제");
                deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
                deleteBtn.setOnAction(e -> handleDeleteImageItem(id));
                ImageView imageView = new ImageView(new Image(imageUrlFinal, widthFinal, heightFinal, true, true));
                imageView.setFitWidth(widthFinal);
                imageView.setFitHeight(heightFinal);
                Button refreshBtn = new Button("새로고침");
                refreshBtn.setOnAction(e -> imageView.setImage(new Image(imageUrlFinal, widthFinal, heightFinal, true, true)));
                newBox.getChildren().addAll(deleteBtn, imageView, refreshBtn);
                newBox.setPrefSize(widthFinal, heightFinal + 56);
                final double[] dragStart = new double[2];
                newBox.setOnMousePressed(e -> {
                    if (!editModeSupplier.getAsBoolean()) return;
                    Point2D p = newBox.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
                    dragStart[0] = p.getX() - newBox.getLayoutX();
                    dragStart[1] = p.getY() - newBox.getLayoutY();
                    e.consume();
                });
                newBox.setOnMouseDragged(e -> {
                    if (!editModeSupplier.getAsBoolean()) return;
                    Point2D p = newBox.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
                    newBox.setLayoutX(applySnap(p.getX() - dragStart[0]));
                    newBox.setLayoutY(applySnap(p.getY() - dragStart[1]));
                    e.consume();
                });
                newBox.setOnMouseReleased(e -> {
                    if (!editModeSupplier.getAsBoolean()) return;
                    apiClient.putJson("/dashboard/images/" + id, Map.of(
                        "x", newBox.getLayoutX(),
                        "y", newBox.getLayoutY(),
                        "width", newBox.getPrefWidth(),
                        "height", newBox.getPrefHeight() - 56,
                        "visible", true,
                        "imageUrl", imageUrlFinal
                    )).exceptionally(ex -> null);
                    e.consume();
                });
                imageItemMap.put(id, newBox);
                classroomContainer.getChildren().add(newBox);
                addImageResizeHandle(newBox, id, imageUrlFinal);
                box = newBox;
            } else {
                if (box.getChildren().size() > 1 && box.getChildren().get(1) instanceof ImageView iv) {
                    iv.setImage(new Image(imageUrlFinal, w, h, true, true));
                    iv.setFitWidth(w);
                    iv.setFitHeight(h);
                }
                box.setPrefSize(w, h + 56);
            }
            box.setLayoutX(x);
            box.setLayoutY(y);
        }
    }

    public void updateImageItemPosition(Long id, double x, double y, double w, double h, boolean visible) {
        apiClient.putJson("/dashboard/images/" + id, Map.of(
            "x", x,
            "y", y,
            "width", w,
            "height", h,
            "visible", visible
        )).exceptionally(ex -> null);
    }

    public void handleDeleteImageItem(Long id) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "이미지를 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
            apiClient.delete("/dashboard/images/" + id)
                        .thenAccept(resp -> loadImageItems());
            }
        });
    }

    public void toggleImageVisibility(Long id, Map<String, Object> map, boolean isVisible) {
        String json = String.format("{\"visible\":%b}", isVisible);
        updateItemVisibility(id, json, "/dashboard/images/");
    }

    private void updateItemVisibility(Long id, String json, String endpoint) {
        apiClient.putJson(endpoint + id, json).exceptionally(ex -> null);
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
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "네트워크 오류 또는 서버 응답 실패: " + cause.getMessage()).show());
                    return null;
                });
    }

    private void addTextResizeHandle(VBox box, TextArea textArea, Long id) {
        Label handle = new Label("◢");
        handle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        VBox handleWrap = new VBox(handle);
        handleWrap.setAlignment(javafx.geometry.Pos.BOTTOM_RIGHT);
        box.getChildren().add(handleWrap);

        handle.setOnMouseDragged(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            Point2D local = box.sceneToLocal(e.getSceneX(), e.getSceneY());
            double w = Math.max(180, applySnap(local.getX()));
            double h = Math.max(120, applySnap(local.getY()));
            box.setPrefSize(w, h);
            textArea.setPrefWidth(w);
            textArea.setPrefHeight(Math.max(80, h - 34));
            e.consume();
        });
        handle.setOnMouseReleased(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
            e.consume();
        });
    }

    private void addImageResizeHandle(VBox box, Long id, String imageUrlFinal) {
        Label handle = new Label("◢");
        handle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        VBox handleWrap = new VBox(handle);
        handleWrap.setAlignment(javafx.geometry.Pos.BOTTOM_RIGHT);
        box.getChildren().add(handleWrap);

        handle.setOnMouseDragged(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            Point2D local = box.sceneToLocal(e.getSceneX(), e.getSceneY());
            double w = Math.max(220, applySnap(local.getX()));
            double h = Math.max(180, applySnap(local.getY()));
            box.setPrefSize(w, h);
            if (box.getChildren().size() > 1 && box.getChildren().get(1) instanceof ImageView iv) {
                iv.setFitWidth(w - 8);
                iv.setFitHeight(Math.max(120, h - 64));
                iv.setImage(new Image(imageUrlFinal, iv.getFitWidth(), iv.getFitHeight(), true, true));
            }
            e.consume();
        });

        handle.setOnMouseReleased(e -> {
            if (!editModeSupplier.getAsBoolean()) return;
            apiClient.putJson("/dashboard/images/" + id, Map.of(
                    "x", box.getLayoutX(),
                    "y", box.getLayoutY(),
                    "width", box.getPrefWidth(),
                    "height", box.getPrefHeight() - 56,
                    "visible", true,
                    "imageUrl", imageUrlFinal
            )).exceptionally(ex -> null);
            e.consume();
        });
    }

    private double applySnap(double value) {
        if (snapModeSupplier == null || !snapModeSupplier.getAsBoolean()) {
            return value;
        }
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }
}
