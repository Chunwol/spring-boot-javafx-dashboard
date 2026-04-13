package com.example.frontend.util;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.AccessibleRole;

/**
 * Accessibility helper utilities.
 * Provides methods to improve app accessibility according to WCAG standards.
 */
public class AccessibilityHelper {

    /**
     * Make a button accessible with text and role
     */
    public static void makeAccessible(Button button, String text, String tooltip) {
        button.setAccessibleText(text);
        button.setAccessibleRole(AccessibleRole.BUTTON);
        if (tooltip != null && !tooltip.isEmpty()) {
            Tooltip.install(button, new Tooltip(tooltip));
        }
    }

    /**
     * Make a ComboBox accessible
     */
    public static void makeAccessible(ComboBox<?> comboBox, String label, String help) {
        comboBox.setAccessibleText(label);
        comboBox.setAccessibleRole(AccessibleRole.COMBO_BOX);
        if (help != null && !help.isEmpty()) {
            comboBox.setAccessibleHelp(help);
        }
    }

    /**
     * Make a label accessible
     */
    public static void makeAccessible(Label label, String text) {
        label.setText(text);
        label.setAccessibleRole(AccessibleRole.TEXT);
    }
}
