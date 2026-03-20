package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.frontend.service.DashboardDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * ViewModel for DashboardController.
 * Manages state and commands, separated from view logic.
 * Responsible for:
 * - UI state (zoom, snap, lock, edit mode, etc.)
 * - Cached data (classrooms, dashboards, panels)
 * - Preferences persistence
 */
public class DashboardViewModel {

    private static final Logger log = LoggerFactory.getLogger(DashboardViewModel.class);

    private static final double MIN_ZOOM = 0.4;
    private static final double MAX_ZOOM = 2.2;
    private static final double GRID_SIZE = 20.0;

    // UI State Properties
    private final DoubleProperty zoomScale = new SimpleDoubleProperty(1.0);
    private final BooleanProperty snapToGrid = new SimpleBooleanProperty(true);
    private final BooleanProperty dashboardOnlyMode = new SimpleBooleanProperty(false);
    private final BooleanProperty focusMode = new SimpleBooleanProperty(false);
    private final BooleanProperty isEditMode = new SimpleBooleanProperty(false);

    // Data Properties
    private final ObjectProperty<Long> selectedDashboardId = new SimpleObjectProperty<>();
    private final ListProperty<ClassroomSummaryDto> cachedClassrooms = new SimpleListProperty<ClassroomSummaryDto>(FXCollections.observableArrayList());
    private final ListProperty<DashboardInfoDto> cachedDashboards = new SimpleListProperty<DashboardInfoDto>(FXCollections.observableArrayList());
    private final ListProperty<DashboardPanelDto> cachedDashboardPanels = new SimpleListProperty<DashboardPanelDto>(FXCollections.observableArrayList());

    // Preferences
    private final Preferences preferences;

    // Data Service
    private final DashboardDataService dataService;

    public DashboardViewModel() {
        this(new DashboardDataService());
    }

    public DashboardViewModel(DashboardDataService dataService) {
        this.preferences = Preferences.userNodeForPackage(DashboardViewModel.class);
        this.dataService = dataService;
        loadPreferences();
    }

    /**
     * Load persisted preferences from system store.
     */
    private void loadPreferences() {
        zoomScale.set(preferences.getDouble("zoomScale", 1.0));
        snapToGrid.set(preferences.getBoolean("snapToGrid", true));
        dashboardOnlyMode.set(preferences.getBoolean("dashboardOnlyMode", false));
    }

    /**
     * Persist current state to preferences.
     */
    public void savePreferences() {
        preferences.putDouble("zoomScale", zoomScale.get());
        preferences.putBoolean("snapToGrid", snapToGrid.get());
        preferences.putBoolean("dashboardOnlyMode", dashboardOnlyMode.get());
    }

    // Zoom commands
    public void zoomIn() {
        double newZoom = Math.min(zoomScale.get() + 0.1, MAX_ZOOM);
        zoomScale.set(newZoom);
        savePreferences();
    }

    public void zoomOut() {
        double newZoom = Math.max(zoomScale.get() - 0.1, MIN_ZOOM);
        zoomScale.set(newZoom);
        savePreferences();
    }

    public void resetZoom() {
        zoomScale.set(1.0);
        savePreferences();
    }

    public void setZoom(double scale) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale));
        zoomScale.set(clamped);
        savePreferences();
    }

    // Toggle commands
    public void toggleSnapToGrid() {
        snapToGrid.set(!snapToGrid.get());
        savePreferences();
    }

    public void toggleEditMode() {
        isEditMode.set(!isEditMode.get());
    }

    public void toggleDashboardOnlyMode() {
        dashboardOnlyMode.set(!dashboardOnlyMode.get());
        savePreferences();
    }

    public void toggleFocusMode() {
        focusMode.set(!focusMode.get());
    }

    // Snap utilities
    public double snapValue(double value) {
        if (!snapToGrid.get()) {
            return value;
        }
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    public Point2D snapPoint(double x, double y) {
        return new Point2D(snapValue(x), snapValue(y));
    }

    // Property accessors
    public DoubleProperty zoomScaleProperty() {
        return zoomScale;
    }

    public double getZoomScale() {
        return zoomScale.get();
    }

    public BooleanProperty snapToGridProperty() {
        return snapToGrid;
    }

    public boolean isSnapToGrid() {
        return snapToGrid.get();
    }

    public void setSnapToGrid(boolean value) {
        snapToGrid.set(value);
        savePreferences();
    }

    public BooleanProperty dashboardOnlyModeProperty() {
        return dashboardOnlyMode;
    }

    public boolean isDashboardOnlyMode() {
        return dashboardOnlyMode.get();
    }

    public void setDashboardOnlyMode(boolean value) {
        dashboardOnlyMode.set(value);
        savePreferences();
    }

    public BooleanProperty focusModeProperty() {
        return focusMode;
    }

    public boolean isFocusMode() {
        return focusMode.get();
    }

    public void setFocusMode(boolean value) {
        focusMode.set(value);
    }

    public BooleanProperty isEditModeProperty() {
        return isEditMode;
    }

    public boolean isEditMode() {
        return isEditMode.get();
    }

    public void setEditMode(boolean value) {
        isEditMode.set(value);
    }

    public ObjectProperty<Long> selectedDashboardIdProperty() {
        return selectedDashboardId;
    }

    public Long getSelectedDashboardId() {
        return selectedDashboardId.get();
    }

    public void setSelectedDashboardId(Long id) {
        selectedDashboardId.set(id);
    }

    public ListProperty<ClassroomSummaryDto> cachedClassroomsProperty() {
        return cachedClassrooms;
    }

    public List<ClassroomSummaryDto> getCachedClassrooms() {
        return cachedClassrooms.get();
    }

    public void setCachedClassrooms(List<ClassroomSummaryDto> classrooms) {
        cachedClassrooms.setAll(classrooms);
    }

    public ListProperty<DashboardInfoDto> cachedDashboardsProperty() {
        return cachedDashboards;
    }

    public List<DashboardInfoDto> getCachedDashboards() {
        return cachedDashboards.get();
    }

    public void setCachedDashboards(List<DashboardInfoDto> dashboards) {
        cachedDashboards.setAll(dashboards);
    }

    public ListProperty<DashboardPanelDto> cachedDashboardPanelsProperty() {
        return cachedDashboardPanels;
    }

    public List<DashboardPanelDto> getCachedDashboardPanels() {
        return cachedDashboardPanels.get();
    }

    public void setCachedDashboardPanels(List<DashboardPanelDto> panels) {
        cachedDashboardPanels.setAll(panels);
    }

    // Constants
    public static double getGridSize() {
        return GRID_SIZE;
    }

    public static double getMinZoom() {
        return MIN_ZOOM;
    }

    public static double getMaxZoom() {
        return MAX_ZOOM;
    }

    // Data Loading Methods
    /**
     * Fetch classrooms from backend using DashboardDataService.
     */
    public void loadClassrooms(Consumer<List<ClassroomSummaryDto>> callback) {
        try {
            List<ClassroomSummaryDto> classrooms = dataService.fetchClassrooms();
            setCachedClassrooms(classrooms);
            if (callback != null) {
                callback.accept(classrooms);
            }
        } catch (Exception e) {
            log.warn("Failed to load classrooms", e);
            if (callback != null) {
                callback.accept(List.of());
            }
        }
    }

    /**
     * Update cached data from a refresh snapshot.
     */
    public void applyRefreshSnapshot(DashboardRefreshDto snapshot) {
        if (snapshot == null) {
            return;
        }
        
        List<ClassroomSummaryDto> classrooms = snapshot.getClassrooms();
        if (classrooms != null) {
            setCachedClassrooms(classrooms);
        }
    }

    /**
     * Fetch refresh snapshot asynchronously using DashboardDataService.
     */
    public void fetchRefreshSnapshotAsync(Consumer<DashboardRefreshDto> callback) {
        dataService.fetchRefreshSnapshotAsync(snapshot -> {
            applyRefreshSnapshot(snapshot);
            if (callback != null) {
                callback.accept(snapshot);
            }
        });
    }
}

