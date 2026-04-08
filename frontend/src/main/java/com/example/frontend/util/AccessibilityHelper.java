package com.example.frontend.util;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.control.AccessibleRole;
import javafx.geometry.Bounds;

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
     * Make a text input accessible
     */
    public static void makeAccessible(TextInputControl field, String label, String help) {
        field.setAccessibleText(label);
        field.setAccessibleRole(AccessibleRole.TEXT_FIELD);
        if (help != null && !help.isEmpty()) {
            field.setAccessibleHelp(help);
        }
    }

    /**
     * Make an image accessible with alt text
     */
    public static void makeAccessible(ImageView imageView, String altText) {
        imageView.setAccessibleText(altText);
        imageView.setAccessibleRole(AccessibleRole.IMAGE_VIEW);
    }

    /**
     * Make a ComboBox accessible
     */
    public static void makeAccessible(ComboBox<?> comboBox, String label) {
        comboBox.setAccessibleText(label);
        comboBox.setAccessibleRole(AccessibleRole.COMBO_BOX);
        if (label != null) {
            comboBox.setAccessibleHelp("선택: " + label);
        }
    }

    /**
     * Make a label accessible
     */
    public static void makeAccessible(Label label, String text) {
        label.setText(text);
        label.setAccessibleRole(AccessibleRole.TEXT);
    }

    /**
     * Add focus style for keyboard navigation visibility
     */
    public static void addFocusStyle(javafx.scene.Node node) {
        node.focusedProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                node.setStyle(node.getStyle() + "; -fx-border-color: #000000; -fx-border-width: 2;");
            } else {
                String style = node.getStyle();
                style = style.replaceAll("; -fx-border-color: #000000; -fx-border-width: 2;", "");
                node.setStyle(style);
            }
        });
    }

    /**
     * Get current focus node for keyboard navigation
     */
    public static javafx.scene.Node getCurrentFocusedNode(javafx.scene.Scene scene) {
        return scene.getFocusOwner();
    }

    /**
     * Scroll node into view if not visible
     */
    public static void scrollIntoView(javafx.scene.Node node, javafx.scene.layout.ScrollPane scrollPane) {
        if (scrollPane == null) {
            return;
        }

        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        Bounds viewport = scrollPane.getViewportBounds();

        double vValue = scrollPane.getVvalue();
        double contentHeight = scrollPane.getContent().prefHeight(-1);
        double viewportHeight = viewport.getHeight();

        if (bounds.getMinY() < viewport.getMinY()) {
            scrollPane.setVvalue(vValue - (viewport.getMinY() - bounds.getMinY()) / contentHeight);
        } else if (bounds.getMaxY() > viewport.getMaxY()) {
            scrollPane.setVvalue(vValue + (bounds.getMaxY() - viewport.getMaxY()) / contentHeight);
        }
    }

    /**
     * Check color contrast ratio (WCAG standard: 4.5:1 for normal text)
     */
    public static double getContrastRatio(javafx.scene.paint.Color foreground, javafx.scene.paint.Color background) {
        double fgLum = getRelativeLuminance(foreground);
        double bgLum = getRelativeLuminance(background);

        double lighter = Math.max(fgLum, bgLum);
        double darker = Math.min(fgLum, bgLum);

        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double getRelativeLuminance(javafx.scene.paint.Color color) {
        double r = color.getRed();
        double g = color.getGreen();
        double b = color.getBlue();

        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);

        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * Check if contrast is WCAG AA compliant (4.5:1)
     */
    public static boolean isWCAG_AA_Compliant(javafx.scene.paint.Color foreground, javafx.scene.paint.Color background) {
        return getContrastRatio(foreground, background) >= 4.5;
    }
}
