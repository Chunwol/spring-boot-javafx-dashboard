package com.example.frontend.service;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.*;
import java.time.LocalDate;
import java.util.Optional;

/**
 * PDF 보고서 다이얼로그 표시 → 서버 API 호출 → 파일 저장.
 *
 * GET /api/v1/reports/pdf?type=WEEKLY
 * GET /api/v1/reports/pdf?type=MONTHLY&year=&month=
 */
public class DashboardPdfService {

    private final String apiBaseUrl;
    private final ApiClient apiClient = ApiClient.getInstance();

    public DashboardPdfService() {
        this("http://localhost:8080/api/v1");
    }

    public DashboardPdfService(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * 보고서 종류 선택 다이얼로그 표시 후 PDF 생성·저장.
     */
    public void showReportDialog(Window ownerWindow) {
        // ── 다이얼로그 구성 ─────────────────────────────────────────────
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("PDF 보고서 생성");
        dialog.setHeaderText("보고서 종류와 기간을 선택하세요");

        ButtonType generateBtn = new ButtonType("생성", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(generateBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));

        // 보고서 유형 선택
        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton weeklyBtn = new RadioButton("주간 보고서 (이번 주)");
        weeklyBtn.setToggleGroup(typeGroup);
        weeklyBtn.setSelected(true);

        RadioButton lastWeekBtn = new RadioButton("주간 보고서 (지난 주)");
        lastWeekBtn.setToggleGroup(typeGroup);

        RadioButton monthlyBtn = new RadioButton("월간 보고서");
        monthlyBtn.setToggleGroup(typeGroup);

        // 월간 선택 시 연/월 입력
        ComboBox<Integer> yearCombo = new ComboBox<>();
        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear - 2; y <= currentYear; y++) yearCombo.getItems().add(y);
        yearCombo.setValue(currentYear);
        yearCombo.setDisable(true);

        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.setValue(LocalDate.now().getMonthValue());
        monthCombo.setDisable(true);

        monthlyBtn.selectedProperty().addListener((obs, o, n) -> {
            yearCombo.setDisable(!n);
            monthCombo.setDisable(!n);
        });

        grid.add(new Label("보고서 유형:"), 0, 0);
        grid.add(weeklyBtn, 1, 0);
        grid.add(lastWeekBtn, 1, 1);
        grid.add(monthlyBtn, 1, 2);
        grid.add(new Label("연도:"), 0, 3);
        grid.add(yearCombo, 1, 3);
        grid.add(new Label("월:"), 0, 4);
        grid.add(monthCombo, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn != generateBtn) return null;
            if (weeklyBtn.isSelected()) return "WEEKLY";
            if (lastWeekBtn.isSelected()) return "WEEKLY_LAST";
            return "MONTHLY:" + yearCombo.getValue() + ":" + monthCombo.getValue();
        });

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() == null) return;

        String choice = result.get();

        // ── 저장 경로 선택 ───────────────────────────────────────────────
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("PDF 보고서 저장");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF 파일", "*.pdf"));
        String defaultName = "report_" + choice.split(":")[0].toLowerCase()
                + "_" + LocalDate.now() + ".pdf";
        fileChooser.setInitialFileName(defaultName);

        File saveFile = fileChooser.showSaveDialog(ownerWindow);
        if (saveFile == null) return;

        // ── 비동기 API 호출 ──────────────────────────────────────────────
        String url = buildUrl(choice);
        apiClient.getBytes(url).thenAccept(bytes -> {
            if (bytes.length == 0) {
                Platform.runLater(() -> showError("PDF 생성 실패", "빈 파일이 반환되었습니다."));
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                fos.write(bytes);
                Platform.runLater(() -> showInfo("저장 완료",
                        "PDF 보고서가 저장되었습니다:\n" + saveFile.getAbsolutePath()));
            } catch (IOException e) {
                Platform.runLater(() -> showError("저장 실패", e.getMessage()));
            }
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            Platform.runLater(() -> showError("PDF 생성 오류", cause.getMessage()));
            return null;
        });
    }

    /** 하위 호환 - 기존 코드에서 호출 시 다이얼로그 표시 */
    public void generateTrafficPdf(Window ownerWindow) throws IOException {
        showReportDialog(ownerWindow);
    }

    // ─── 내부 유틸 ────────────────────────────────────────────────────────

    private String buildUrl(String choice) {
        if (choice.startsWith("MONTHLY:")) {
            String[] parts = choice.split(":");
            return apiBaseUrl + "/reports/pdf?type=MONTHLY&year=" + parts[1] + "&month=" + parts[2];
        }
        return apiBaseUrl + "/reports/pdf?type=WEEKLY";
    }

    private static void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private static void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }
}
