package com.example.agent.ui;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;

public class FileOrFolderTransferDialog {
    private final JDialog dialog;
    private final JTextField pathField;
    private final JCheckBox runAfterTransferCheck;
    private List<File> selectedFiles = List.of();
    private File selectedFolder;

    public FileOrFolderTransferDialog(BiConsumer<List<File>, Boolean> onSendFiles, BiConsumer<File, Boolean> onSendFolder) {
        dialog = new JDialog((java.awt.Frame) null, "파일/폴더 전송", true);

        pathField = new JTextField();
        pathField.setEditable(false);
        runAfterTransferCheck = new JCheckBox("전송 후 실행");
        JButton fileButton = new JButton("파일 선택");
        JButton folderButton = new JButton("폴더 선택");
        JButton sendButton = new JButton("전송");
        JButton cancelButton = new JButton("취소");

        fileButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("전송할 파일 선택");
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int ret = chooser.showOpenDialog(dialog);
            if (ret != JFileChooser.APPROVE_OPTION) return;
            File[] filesArr = chooser.getSelectedFiles();
            List<File> files = java.util.Arrays.asList(filesArr);
            if (files != null && !files.isEmpty()) {
                selectedFiles = files;
                selectedFolder = null;
                pathField.setText(files.size() == 1 ? files.get(0).getAbsolutePath() : files.size() + "개 파일 선택됨");
            }
        });
        folderButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("전송할 폴더 선택");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int ret = chooser.showOpenDialog(dialog);
            if (ret != JFileChooser.APPROVE_OPTION) return;
            File folder = chooser.getSelectedFile();
            if (folder != null) {
                selectedFolder = folder;
                selectedFiles = List.of();
                pathField.setText(folder.getAbsolutePath());
            }
        });
        sendButton.addActionListener(e -> {
            boolean runAfter = runAfterTransferCheck.isSelected();
            if (!selectedFiles.isEmpty()) {
                onSendFiles.accept(selectedFiles, runAfter);
            } else if (selectedFolder != null) {
                onSendFolder.accept(selectedFolder, runAfter);
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("전송할 파일 또는 폴더를 선택하세요."));
        panel.add(Box.createVerticalStrut(8));
        panel.add(pathField);
        panel.add(Box.createVerticalStrut(10));
        JPanel selectBox = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        selectBox.add(fileButton);
        selectBox.add(folderButton);
        panel.add(selectBox);
        panel.add(runAfterTransferCheck);
        JPanel actionBox = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        actionBox.add(sendButton);
        actionBox.add(cancelButton);
        panel.add(actionBox);

        dialog.setContentPane(panel);
        dialog.setSize(460, 230);
        dialog.setLocationRelativeTo(null);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
    }
}
