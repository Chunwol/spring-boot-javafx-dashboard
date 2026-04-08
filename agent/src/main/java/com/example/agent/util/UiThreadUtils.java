package com.example.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI Thread safety utilities.
 * Ensures UI operations are executed on the appropriate thread.
 */
public class UiThreadUtils {

    private static final Logger log = LoggerFactory.getLogger(UiThreadUtils.class);

    /**
     * Check if running on Swing/AWT event dispatch thread
     * @return true if on EDT, false otherwise
     */
    public static boolean isSwingThread() {
        try {
            Class<?> swingUtils = Class.forName("javax.swing.SwingUtilities");
            java.lang.reflect.Method method = swingUtils.getMethod("isEventDispatchThread");
            return (Boolean) method.invoke(null);
        } catch (Exception e) {
            log.debug("Swing thread check 실패 (Swing 없음?): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if running on JavaFX application thread
     * @return true if on JavaFX thread, false otherwise
     */
    public static boolean isJavaFxThread() {
        try {
            Class<?> platform = Class.forName("javafx.application.Platform");
            java.lang.reflect.Method method = platform.getMethod("isFxApplicationThread");
            return (Boolean) method.invoke(null);
        } catch (Exception e) {
            log.debug("JavaFX thread check 실패 (JavaFX 없음?): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Run on Swing/AWT EDT if not already on it
     * @param runnable Code to run on EDT
     */
    public static void runOnSwingThread(Runnable runnable) {
        if (isSwingThread()) {
            runnable.run();
        } else {
            try {
                Class<?> swingUtils = Class.forName("javax.swing.SwingUtilities");
                java.lang.reflect.Method method = swingUtils.getMethod("invokeLater", Runnable.class);
                method.invoke(null, runnable);
            } catch (Exception e) {
                log.error("Swing thread로 작업 스케줄 실패: {}", e.getMessage());
            }
        }
    }

    /**
     * Run on JavaFX application thread if not already on it
     * @param runnable Code to run on FX thread
     */
    public static void runOnJavaFxThread(Runnable runnable) {
        if (isJavaFxThread()) {
            runnable.run();
        } else {
            try {
                Class<?> platform = Class.forName("javafx.application.Platform");
                java.lang.reflect.Method method = platform.getMethod("runLater", Runnable.class);
                method.invoke(null, runnable);
            } catch (Exception e) {
                log.error("JavaFX thread로 작업 스케줄 실패: {}", e.getMessage());
            }
        }
    }

    /**
     * Log warning if running on non-UI thread when UI operation attempted
     * @param operationName Name of the UI operation for logging
     */
    public static void warnIfNonUiThread(String operationName) {
        if (!isSwingThread() && !isJavaFxThread()) {
            String threadName = Thread.currentThread().getName();
            log.warn("UI 작업{}이 UI 스레드가 아닌 {}에서 호출됨", operationName, threadName);
        }
    }
}
