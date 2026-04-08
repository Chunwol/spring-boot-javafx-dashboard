package com.example.agent.ui;

import javax.swing.*;
import java.awt.*;

public class FileTransferProgressDialog {
    private final JDialog dialog;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JLabel etaLabel;

    public FileTransferProgressDialog(String fileName, long fileSize, Runnable onCancel) {
        dialog = new JDialog((Frame) null, "파일 전송중", false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

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
            dialog.dispose();
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

        dialog.setContentPane(panel);
        dialog.setSize(380, 220);
        dialog.setLocationRelativeTo(null);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
    }

    public void close() {
        SwingUtilities.invokeLater(dialog::dispose);
    }

    public void setProgress(double progress) {
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
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    public void setEta(String eta) {
        SwingUtilities.invokeLater(() -> etaLabel.setText("남은 시간: " + eta));
    }

    private static String humanReadableByteCount(long bytes) {
        int unit = 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = ("KMGTPE").charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
