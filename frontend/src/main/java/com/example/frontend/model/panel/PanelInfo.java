package com.example.frontend.model.panel;

import javafx.scene.layout.VBox;

public class PanelInfo {
    public enum PanelType { CLASSROOM, TEXT, IMAGE, NETWORK }
    private final long id;
    private final PanelType type;
    private final VBox panel;

    public PanelInfo(long id, PanelType type, VBox panel) {
        this.id = id;
        this.type = type;
        this.panel = panel;
    }

    public long getId() { return id; }
    public PanelType getType() { return type; }
    public VBox getPanel() { return panel; }
}
