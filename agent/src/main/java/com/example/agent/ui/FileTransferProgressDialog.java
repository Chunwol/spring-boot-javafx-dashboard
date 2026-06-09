package com.example.agent.ui;

import javax.swing.*;
import java.awt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 파일 전송 진행 상태 다이얼로그.
 * SYSTEM/Session-0 환경(headless) 또는 AWT 초기화 실패 시 모든 메서드가 no-op으로 동작한다.
 */
public class FileTransferProgressDialog {

    private static final Logger log = LoggerFactory.getLogger(FileTransferProgressDialog.class);

    private JDialog dialog;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel etaLabel;

    public FileTransferProgressDialog(String fileName, long fileSize, Runnable onCancel) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return; // SYSTEM/Session-0 — 다이얼로그 비활성화
            }
            JDialog d = new JDialog((Frame) null, "파일 전송중", false);
            d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

            JLabel fileNameLabel = new JLabel("파일명: " + fileName);
            JLabel fileSizeLabel = new JLabel("용량: " + (fileSize > 0 ? humanReadableByteCount(fileSize) : "확인중"));
            statusLabel = new JLabel("전송중입니다...");
            etaLabel = new JLabel("남은 시간: 계산중...");

            progressBar = new JProgressBar(0, 1000);
            progressBar.setValue(0);
            progressBar.setStringPainted(true);

            JButton cancelButton = new JButton("취소");
            cancelButton.addActionListener(e -> {
                if (onCancel != null) onCancel.run();
                d.dispose();
            });

            panel.add(fileNameLabel);
            panel.add(Box.createVerticalStrut(6));
            panel.add(fileSizeLabel);
            panel.add(Box.createVerticalStrut(10));
            panel.add(progressBar);
            panel.add(Box.createVerticalStrut(10));
            panel.add(statusLabel);
            panel.add(Box.createVerticalStrut(6));
            panel.add(etaLabel);
            panel.add(Box.createVerticalStrut(12));
            panel.add(cancelButton);

            d.setContentPane(panel);
            d.setSize(380, 220);
            d.setLocationRelativeTo(null);
            dialog = d;
        } catch (Throwable t) {
            // AWTError, HeadlessException 등 — headless 환경이므로 다이얼로그 없이 진행
            log.debug("파일 전송 다이얼로그 비활성화 (headless 또는 AWT 오류): {}", t.getMessage());
            dialog = null;
            progressBar = null;
            statusLabel = null;
            etaLabel = null;
        }
    }

    public void show() {
        if (dialog == null) return;
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
    }

    public void close() {
        if (dialog == null) return;
        SwingUtilities.invokeLater(dialog::dispose);
    }

    public void setProgress(double progress) {
        if (progressBar == null) return;
        SwingUtilities.invokeLater(() -> {
            if (progress < 0) {
                progressBar.setIndeterminate(true);
                return;
            }
            progressBar.setIndeterminate(false);
            int v = (int) Math.max(0, Math.min(1000, Math.round(progress * 1000)));
            progressBar.setValue(v);
        });
    }

    public void setStatus(String status) {
        if (statusLabel == null) return;
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    public void setEta(String eta) {
        if (etaLabel == null) return;
        SwingUtilities.invokeLater(() -> etaLabel.setText("남은 시간: " + eta));
    }

    private static String humanReadableByteCount(long bytes) {
        int unit = 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = ("KMGTPE").charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
