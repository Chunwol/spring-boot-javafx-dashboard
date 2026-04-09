package com.example.frontend.util;

import javafx.application.Platform;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

/**
 * Theme manager for the application.
 * Handles switching between Light, Dark, and High Contrast themes.
 */
public class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);
    private static final String PREF_KEY_THEME = "theme";
    // volatile: 스레드 간 변경 가시성 보장
    private static volatile ThemeColorConstants.Theme currentTheme = ThemeColorConstants.Theme.LIGHT;
    private static volatile Scene currentScene;
    private static volatile Runnable themeChangeCallback;

    static {
        // Load saved theme preference
        Preferences prefs = Preferences.userNodeForPackage(ThemeManager.class);
        String savedTheme = prefs.get(PREF_KEY_THEME, "LIGHT");
        try {
            currentTheme = ThemeColorConstants.Theme.valueOf(savedTheme);
        } catch (IllegalArgumentException e) {
            currentTheme = ThemeColorConstants.Theme.LIGHT;
        }
    }

    public static synchronized void setTheme(ThemeColorConstants.Theme theme) {
        currentTheme = theme;
        savePreference(theme);
        applyTheme(theme);

        Runnable callback = themeChangeCallback;
        if (callback != null) {
            callback.run();
        }
        log.info("Theme changed to: {}", theme);
    }

    public static synchronized void setCurrentScene(Scene scene) {
        currentScene = scene;
        applyTheme(currentTheme);
    }

    public static ThemeColorConstants.Theme getCurrentTheme() {
        return currentTheme;
    }

    private static synchronized void applyTheme(ThemeColorConstants.Theme theme) {
        Scene scene = currentScene;
        if (scene == null || scene.getRoot() == null) {
            return;
        }

        Platform.runLater(() -> {
            scene.getRoot().getStyleClass().removeAll("dark-theme", "high-contrast-theme", "light-theme");

            switch (theme) {
                case DARK:
                    scene.getRoot().getStyleClass().add("dark-theme");
                    break;
                case HIGH_CONTRAST:
                    scene.getRoot().getStyleClass().add("high-contrast-theme");
                    break;
                default:
                    scene.getRoot().getStyleClass().add("light-theme");
            }
        });
    }

    private static void savePreference(ThemeColorConstants.Theme theme) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(ThemeManager.class);
            prefs.put(PREF_KEY_THEME, theme.name());
            prefs.flush();
        } catch (Exception e) {
            log.error("Failed to save theme preference", e);
        }
    }

    public static void setThemeChangeCallback(Runnable callback) {
        themeChangeCallback = callback;
    }

    public static boolean isDarkMode() {
        return currentTheme == ThemeColorConstants.Theme.DARK;
    }

    public static boolean isHighContrast() {
        return currentTheme == ThemeColorConstants.Theme.HIGH_CONTRAST;
    }
}
