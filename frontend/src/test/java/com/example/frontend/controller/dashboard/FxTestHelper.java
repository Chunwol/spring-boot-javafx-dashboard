package com.example.frontend.controller.dashboard;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;

/**
 * Keeps the JavaFX platform alive for the entire test-JVM lifetime.
 *
 * Problem: {@code new JFXPanel()} in {@code @BeforeAll} without a stored reference
 * is immediately GC-eligible.  Once the last live JFXPanel is collected the
 * toolkit exits, {@code Platform.runLater} tasks are silently dropped, and
 * latches never count down → all FX tests time-out.  JavaFX cannot be restarted
 * in the same JVM after exit.
 *
 * Solution: store the panel in a static field of this class.  Static fields of
 * loaded classes are held by the class-loader and survive until JVM shutdown,
 * so the toolkit stays alive across all test-class boundaries.
 */
public final class FxTestHelper {

    private static final JFXPanel PANEL;

    static {
        PANEL = new JFXPanel();                 // initialises the FX platform
        Platform.setImplicitExit(false);        // prevent exit when stages close
    }

    /** Call from {@code @BeforeAll} in any test class that uses the FX thread. */
    public static void initToolkit() {
        // Referencing this class loads the static initialiser exactly once.
        // Subsequent calls are free (class already loaded).
    }

    private FxTestHelper() {}
}
