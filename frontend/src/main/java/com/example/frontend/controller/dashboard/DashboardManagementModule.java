package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.model.dashboard.IpRangeItem;
import com.example.frontend.model.dashboard.TeacherDeviceItem;
import com.example.util.MultipartUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class DashboardManagementModule {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Supplier<javafx.scene.Scene> sceneSupplier;
    private final ApiClient apiClient;

    private ComboBox<ClassroomSummaryDto> groupComboBox;
    private ComboBox<ClassroomSummaryDto> teacherGroupCombo;
    private TableView<IpRangeItem> ipRangeTable;
    private TableView<TeacherDeviceItem> teacherDeviceTable;

    public DashboardManagementModule(HttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     String apiBaseUrl,
                                     Supplier<javafx.scene.Scene> sceneSupplier) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.sceneSupplier = sceneSupplier;
        this.apiClient = ApiClient.getInstance();
    }

    public Node createView() {
        VBox container = new VBox(20);
        container.setPadding(new Insets(20));

        VBox scriptBox = buildScriptBox();
        VBox ipBox = buildIpRangeBox();
        VBox teacherBox = buildTeacherBox();
        VBox reportBox = buildReportBox();

        container.getChildren().addAll(scriptBox, ipBox, teacherBox, reportBox);
        loadIpRangesAndGroups();
        return new ScrollPane(container);
    }

    private VBox buildScriptBox() {
        Label scriptTitle = new Label("원격 명령 및 스크립트 배포");
        scriptTitle.setFont(Font.font("Arial", FontWeight.BOLD, 18));

        Button uploadPs1Btn = new Button("PowerShell(.ps1) 파일 선택 및 배포...");
        uploadPs1Btn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-size: 14px;");
        uploadPs1Btn.setOnAction(e -> handleScriptUploadAndRun());

        VBox scriptBox = new VBox(10, scriptTitle, uploadPs1Btn);
        scriptBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return scriptBox;
    }

    private VBox buildIpRangeBox() {
        Label ipTitle = new Label("강의실 IP 대역 관리");
        ipTitle.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        Label ipHint = new Label("지원 형식: 시작/끝 IP 또는 다중 대역(예: 10-20,30-40 / 10.0.1.10-10.0.1.20)");
        ipHint.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 12px;");

        groupComboBox = buildClassroomComboBox();
        groupComboBox.setPromptText("강의실 선택");
        groupComboBox.setPrefWidth(180);

        TextField startIpField = new TextField();
        startIpField.setPromptText("학생 시작 IP");
        startIpField.setPrefWidth(160);
        TextField endIpField = new TextField();
        endIpField.setPromptText("학생 끝 IP");
        endIpField.setPrefWidth(160);
        TextField basePrefixField = new TextField();
        basePrefixField.setPromptText("Base Prefix (예: 10.0.1.)");
        basePrefixField.setPrefWidth(170);
        TextArea rangeSpecArea = new TextArea();
        rangeSpecArea.setPromptText("다중 대역 입력\n예) 10-20,30-40 또는 10.0.1.10-10.0.1.20,10.0.1.30-10.0.1.40");
        rangeSpecArea.setPrefRowCount(2);
        rangeSpecArea.setWrapText(true);
        rangeSpecArea.setPrefWidth(360);
        TextField descField = new TextField();
        descField.setPromptText("설명");
        descField.setPrefWidth(180);
        Button addIpRangeBtn = new Button("대역 추가");
        addIpRangeBtn.setOnAction(e -> addIpRange(startIpField, endIpField, basePrefixField, rangeSpecArea, descField));

        HBox ipForm = new HBox(10, groupComboBox, startIpField, endIpField, basePrefixField, descField, addIpRangeBtn);
        ipForm.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(descField, javafx.scene.layout.Priority.ALWAYS);

        ipRangeTable = new TableView<>();
        ipRangeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        ipRangeTable.setPrefHeight(320);

        TableColumn<IpRangeItem, Long> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        TableColumn<IpRangeItem, String> groupNameCol = new TableColumn<>("강의실");
        groupNameCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<IpRangeItem, String> cidrCol = new TableColumn<>("CIDR");
        cidrCol.setCellValueFactory(new PropertyValueFactory<>("cidr"));
        TableColumn<IpRangeItem, String> startCol = new TableColumn<>("Start IP");
        startCol.setCellValueFactory(new PropertyValueFactory<>("startIp"));
        TableColumn<IpRangeItem, String> endCol = new TableColumn<>("End IP");
        endCol.setCellValueFactory(new PropertyValueFactory<>("endIp"));
        TableColumn<IpRangeItem, String> descCol = new TableColumn<>("Desc");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<IpRangeItem, Void> actionCol = new TableColumn<>("Action");
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            {
                deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                deleteBtn.setOnAction(event -> {
                    IpRangeItem item = getTableView().getItems().get(getIndex());
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "정말 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
                    alert.showAndWait().ifPresent(type -> {
                        if (type == ButtonType.YES) {
                            deleteIpRange(item);
                        }
                    });
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });

        ipRangeTable.getColumns().addAll(idCol, groupNameCol, cidrCol, startCol, endCol, descCol, actionCol);
        VBox ipBox = new VBox(10, ipTitle, ipHint, ipForm, rangeSpecArea, ipRangeTable);
        ipBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return ipBox;
    }

    private VBox buildTeacherBox() {
        Label teacherTitle = new Label("교사 PC 관리");
        teacherTitle.setFont(Font.font("Arial", FontWeight.BOLD, 18));

        teacherGroupCombo = buildClassroomComboBox();
        teacherGroupCombo.setPromptText("강의실 선택");
        teacherGroupCombo.setOnAction(e -> loadTeacherDevices());

        TextField teacherIp = new TextField();
        teacherIp.setPromptText("교사 IP");
        TextField teacherMac = new TextField();
        teacherMac.setPromptText("교사 MAC");
        TextField teacherDesc = new TextField();
        teacherDesc.setPromptText("설명");
        Button addTeacherBtn = new Button("교사 등록");
        addTeacherBtn.setOnAction(e -> addTeacherDevice(teacherIp, teacherMac, teacherDesc));

        teacherDeviceTable = new TableView<>();
        teacherDeviceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<TeacherDeviceItem, Long> teacherIdCol = new TableColumn<>("ID");
        teacherIdCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        TableColumn<TeacherDeviceItem, String> teacherGroupCol = new TableColumn<>("강의실");
        teacherGroupCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<TeacherDeviceItem, String> teacherIpCol = new TableColumn<>("IP");
        teacherIpCol.setCellValueFactory(new PropertyValueFactory<>("ipAddress"));
        TableColumn<TeacherDeviceItem, String> teacherMacCol = new TableColumn<>("MAC");
        teacherMacCol.setCellValueFactory(new PropertyValueFactory<>("macAddress"));
        TableColumn<TeacherDeviceItem, String> teacherDescCol = new TableColumn<>("설명");
        teacherDescCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<TeacherDeviceItem, Void> teacherActionCol = new TableColumn<>("액션");
        teacherActionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            {
                deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                deleteBtn.setOnAction(event -> {
                    TeacherDeviceItem item = getTableView().getItems().get(getIndex());
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(apiBaseUrl + "/dashboard/classrooms/teacher-devices/" + item.getId()))
                            .DELETE()
                            .build();
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                            .thenAccept(r -> Platform.runLater(this::refreshTeacherDevices));
                });
            }

            private void refreshTeacherDevices() {
                loadTeacherDevices();
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });
        teacherDeviceTable.getColumns().addAll(teacherIdCol, teacherGroupCol, teacherIpCol, teacherMacCol, teacherDescCol, teacherActionCol);

        Label brokenTitle = new Label("고장 PC 등록");
        brokenTitle.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        TextField brokenMacField = new TextField();
        brokenMacField.setPromptText("MAC 주소");
        TextField brokenNoteField = new TextField();
        brokenNoteField.setPromptText("고장 사유");
        Button brokenRegisterBtn = new Button("고장 등록");
        brokenRegisterBtn.setOnAction(e -> markBrokenPc(brokenMacField, brokenNoteField));

        HBox brokenBox = new HBox(10, brokenMacField, brokenNoteField, brokenRegisterBtn);
        HBox teacherForm = new HBox(10, teacherGroupCombo, teacherIp, teacherMac, teacherDesc, addTeacherBtn);
        VBox teacherBox = new VBox(10, teacherTitle, teacherForm, teacherDeviceTable, brokenTitle, brokenBox);
        teacherBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return teacherBox;
    }

    private VBox buildReportBox() {
        Label reportTitle = new Label("주간/월간 보고서");
        reportTitle.setFont(Font.font("Arial", FontWeight.BOLD, 18));

        HBox reportBtns = new HBox(15);
        Button weekBtn = new Button("주간 보고서 생성");
        weekBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        weekBtn.setOnAction(e -> handleDownloadReport("WEEKLY"));

        Button monthBtn = new Button("월간 보고서 생성");
        monthBtn.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        monthBtn.setOnAction(e -> handleDownloadReport("MONTHLY"));

        reportBtns.getChildren().addAll(weekBtn, monthBtn);
        VBox reportBox = new VBox(10, reportTitle, reportBtns);
        reportBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return reportBox;
    }

    private ComboBox<ClassroomSummaryDto> buildClassroomComboBox() {
        ComboBox<ClassroomSummaryDto> comboBox = new ComboBox<>();
        comboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ClassroomSummaryDto object) {
                return object != null ? object.getGroupName() : "";
            }

            @Override
            public ClassroomSummaryDto fromString(String string) {
                return null;
            }
        });
        return comboBox;
    }

    private void loadIpRangesAndGroups() {
        apiClient.getJson("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
            .thenAccept(classrooms -> {
                try {
                        Map<Long, String> groupMap = classrooms.stream().collect(java.util.stream.Collectors.toMap(ClassroomSummaryDto::getGroupId, ClassroomSummaryDto::getGroupName));

                        Platform.runLater(() -> {
                            groupComboBox.getItems().setAll(classrooms);
                            teacherGroupCombo.getItems().setAll(classrooms);
                            if (!classrooms.isEmpty()) {
                                teacherGroupCombo.getSelectionModel().selectFirst();
                                loadTeacherDevices();
                            }
                        });

                        apiClient.getJson("/ip-ranges", new TypeReference<List<IpRangeItem>>() {})
                                .thenAccept(items -> {
                                    items.forEach(item -> item.setGroupName(groupMap.getOrDefault(item.getGroupId(), "N/A")));
                                    Platform.runLater(() -> ipRangeTable.getItems().setAll(items));
                                })
                                .exceptionally(ex -> {
                                    ex.printStackTrace();
                                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "IP 대역 목록 조회 실패: " + ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()).show());
                                    return null;
                                });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "강의실 목록 조회 실패: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                    return null;
                });
    }

    private void loadTeacherDevices() {
        ClassroomSummaryDto selected = teacherGroupCombo.getValue();
        if (selected == null) {
            return;
        }
        apiClient.getJson("/dashboard/classrooms/" + selected.getGroupId() + "/teacher-devices", new TypeReference<List<TeacherDeviceItem>>() {})
                .thenAccept(items -> {
                    items.forEach(it -> it.setGroupName(selected.getGroupName()));
                    Platform.runLater(() -> teacherDeviceTable.getItems().setAll(items));
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "교사 PC 목록 조회 실패: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                    return null;
                });
    }

    private void addTeacherDevice(TextField ipTF, TextField macTF, TextField descTF) {
        ClassroomSummaryDto selectedGroup = teacherGroupCombo.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groupId", selectedGroup.getGroupId());
            payload.put("ipAddress", ipTF.getText());
            payload.put("macAddress", macTF.getText());
            payload.put("description", descTF.getText());
            apiClient.postJson("/dashboard/classrooms/" + selectedGroup.getGroupId() + "/teacher-devices", payload)
                    .thenAccept(resp -> Platform.runLater(() -> {
                        ipTF.clear();
                        macTF.clear();
                        descTF.clear();
                        loadTeacherDevices();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "교사 PC 등록 실패: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                        return null;
                    });
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "입력 값을 확인하세요.").show();
        }
    }

    private void markBrokenPc(TextField macTF, TextField noteTF) {
        String mac = macTF.getText();
        if (mac == null || mac.isBlank()) {
            new Alert(Alert.AlertType.ERROR, "MAC 주소를 입력하세요.").show();
            return;
        }
        String note = noteTF.getText();
        apiClient.postJson("/dashboard/pcs/broken", Map.of("macAddress", mac, "note", note == null ? "broken" : note))
                .thenAccept(resp -> Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "고장 PC로 등록되었습니다.").show();
                    macTF.clear();
                    noteTF.clear();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "등록 실패: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                    return null;
                });
    }

    private void addIpRange(TextField startIp, TextField endIp, TextField basePrefix, TextArea rangeSpec, TextField desc) {
        ClassroomSummaryDto selectedGroup = groupComboBox.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
            return;
        }

        try {
            String start = startIp.getText() == null ? "" : startIp.getText().trim();
            String end = endIp.getText() == null ? "" : endIp.getText().trim();
            String multi = rangeSpec.getText() == null ? "" : rangeSpec.getText().trim();

            CompletableFuture<String> future;
            if (!multi.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("groupId", selectedGroup.getGroupId());
                payload.put("basePrefix", basePrefix.getText());
                payload.put("rangeSpec", multi);
                payload.put("description", desc.getText());
                future = apiClient.postJson("/ip-ranges/batch", payload);
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("groupId", selectedGroup.getGroupId());
                payload.put("startIp", start);
                payload.put("endIp", end);
                payload.put("description", desc.getText());
                future = apiClient.postJson("/ip-ranges", payload);
            }

            future.thenAccept(resp -> Platform.runLater(() -> {
                        groupComboBox.getSelectionModel().clearSelection();
                        startIp.clear();
                        endIp.clear();
                        basePrefix.clear();
                        rangeSpec.clear();
                        desc.clear();
                        loadIpRangesAndGroups();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "추가 실패\n" + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                        return null;
                    });
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "입력 값을 확인하세요.").show();
        }
    }

    private void deleteIpRange(IpRangeItem item) {
        apiClient.delete("/ip-ranges/" + item.getId())
                .thenAccept(resp -> Platform.runLater(this::loadIpRangesAndGroups))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "삭제 실패: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())).show());
                    return null;
                });
    }

    // ─── 스크립트 / 보고서 ────────────────────────────────────────────────

    private void handleScriptUploadAndRun() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("PowerShell 스크립트 선택");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerShell Files", "*.ps1"));
        File file = fileChooser.showOpenDialog(sceneSupplier.get().getWindow());

        if (file != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "'" + file.getName() + "' 파일을 모든 PC에 배포하고 실행하시겠습니까?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(type -> {
                if (type == ButtonType.YES) uploadAndExecuteScript(file);
            });
        }
    }

    private void uploadAndExecuteScript(File file) {
        new Thread(() -> {
            try {
                HttpRequest uploadRequest = MultipartUtil
                        .newMultipartRequest(apiBaseUrl + "/scripts", Map.of("file", file.toPath().toString()))
                        .build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());

                // 업로드 응답에서 scriptId 추출 (서버가 JSON {"id":xxx} 반환 가정)
                String scriptId;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(uploadResponse.body(), Map.class);
                    scriptId = resp.getOrDefault("id", "0").toString();
                } catch (Exception e) {
                    scriptId = "0";
                }

                String jsonBody = String.format(
                        "{\"scope\":\"BROADCAST\",\"type\":\"RUN_PS1\",\"role\":\"ALL\",\"payload\":\"%s\"}", scriptId);
                HttpRequest cmdRequest = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/commands"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                httpClient.sendAsync(cmdRequest, HttpResponse.BodyHandlers.discarding());

                final int statusCode = uploadResponse.statusCode();
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                        "스크립트 업로드 및 실행 요청 완료 (" + statusCode + ")").show());
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "스크립트 업로드 오류: " + e.getMessage()).show());
            }
        }).start();
    }

    private void handleDownloadReport(String type) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(type + " 보고서 PDF 저장");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        String defaultName = "WEEKLY".equals(type) ? "주간보고서" : "월간보고서";
        fileChooser.setInitialFileName(defaultName + "_" + LocalDate.now() + ".pdf");

        File file = fileChooser.showSaveDialog(sceneSupplier.get().getWindow());
        if (file == null) return;

        javafx.scene.Scene scene = sceneSupplier.get();
        scene.setCursor(Cursor.WAIT);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/reports?type=" + type))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    Platform.runLater(() -> scene.setCursor(Cursor.DEFAULT));
                    if (response.statusCode() == 200) {
                        try {
                            Files.write(file.toPath(), response.body());
                            Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                                    "보고서가 저장되었습니다:\n" + file.getAbsolutePath()).show());
                        } catch (IOException e) {
                            e.printStackTrace();
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "파일 저장 중 오류가 발생했습니다.").show());
                        }
                    } else {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "보고서 생성 실패 (상태 코드: " + response.statusCode() + ")").show());
                    }
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        scene.setCursor(Cursor.DEFAULT);
                        new Alert(Alert.AlertType.ERROR, "네트워크 오류: " + e.getMessage()).show();
                    });
                    return null;
                });
    }
}


