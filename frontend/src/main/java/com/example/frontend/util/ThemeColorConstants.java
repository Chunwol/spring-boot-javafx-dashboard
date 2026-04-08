package com.example.frontend.util;

import javafx.scene.paint.Color;

/**
 * Centralized theme color constants for consistent styling.
 */
public class ThemeColorConstants {

    public enum Theme {
        LIGHT, DARK, HIGH_CONTRAST
    }

    // Light Theme Colors (Default)
    public static class Light {
        public static final Color PRIMARY = Color.web("#3182ce");
        public static final Color PRIMARY_HOVER = Color.web("#2563eb");
        public static final Color SUCCESS = Color.web("#10b981");
        public static final Color DANGER = Color.web("#d73a49");
        public static final Color WARNING = Color.web("#d97706");
        public static final Color INFO = Color.web("#3b82f6");

        public static final Color BG = Color.web("#ffffff");
        public static final Color BG_SECONDARY = Color.web("#f0f4ff");
        public static final Color BORDER = Color.web("#cbd5e0");
        public static final Color TEXT = Color.web("#0f172a");
        public static final Color TEXT_SECONDARY = Color.web("#64748b");
        public static final Color TEXT_DISABLED = Color.web("#a0aec0");

        public static final Color ONLINE = Color.web("#22a06b");
        public static final Color OFFLINE = Color.web("#334155");
        public static final Color FAILED = Color.web("#d55f74");
    }

    // Dark Theme Colors
    public static class Dark {
        public static final Color PRIMARY = Color.web("#60a5fa");
        public static final Color PRIMARY_HOVER = Color.web("#3b82f6");
        public static final Color SUCCESS = Color.web("#34d399");
        public static final Color DANGER = Color.web("#f87171");
        public static final Color WARNING = Color.web("#fbbf24");

        public static final Color BG = Color.web("#1f2937");
        public static final Color BG_SECONDARY = Color.web("#111827");
        public static final Color BORDER = Color.web("#374151");
        public static final Color TEXT = Color.web("#f3f4f6");
        public static final Color TEXT_SECONDARY = Color.web("#9ca3af");
        public static final Color TEXT_DISABLED = Color.web("#6b7280");

        public static final Color ONLINE = Color.web("#4ade80");
        public static final Color OFFLINE = Color.web("#9ca3af");
        public static final Color FAILED = Color.web("#fb7185");
    }

    // High Contrast Theme
    public static class HighContrast {
        public static final Color PRIMARY = Color.BLACK;
        public static final Color SUCCESS = Color.BLACK;
        public static final Color DANGER = Color.BLACK;

        public static final Color BG = Color.WHITE;
        public static final Color BORDER = Color.BLACK;
        public static final Color TEXT = Color.BLACK;
        public static final Color TEXT_SECONDARY = Color.BLACK;
    }

    public static Color getColor(Theme theme, String colorKey) {
        return switch (theme) {
            case LIGHT -> getLightColor(colorKey);
            case DARK -> getDarkColor(colorKey);
            case HIGH_CONTRAST -> getHighContrastColor(colorKey);
        };
    }

    private static Color getLightColor(String key) {
        return switch (key) {
            case "primary" -> Light.PRIMARY;
            case "success" -> Light.SUCCESS;
            case "danger" -> Light.DANGER;
            case "bg" -> Light.BG;
            case "text" -> Light.TEXT;
            default -> Light.TEXT;
        };
    }

    private static Color getDarkColor(String key) {
        return switch (key) {
            case "primary" -> Dark.PRIMARY;
            case "success" -> Dark.SUCCESS;
            case "danger" -> Dark.DANGER;
            case "bg" -> Dark.BG;
            case "text" -> Dark.TEXT;
            default -> Dark.TEXT;
        };
    }

    private static Color getHighContrastColor(String key) {
        return switch (key) {
            case "bg" -> HighContrast.BG;
            case "text" -> HighContrast.TEXT;
            case "border" -> HighContrast.BORDER;
            default -> HighContrast.TEXT;
        };
    }
}
