package com.example.frontend.controller.dashboard;

import javafx.geometry.Point2D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardViewModelTest {

    @BeforeEach
    void clearPreferences() throws BackingStoreException {
        Preferences prefs = Preferences.userNodeForPackage(DashboardViewModel.class);
        prefs.remove("zoomScale");
        prefs.remove("snapToGrid");
        prefs.remove("layoutLocked");
        prefs.remove("dashboardOnlyMode");
        prefs.flush();
    }

    @Test
    void setZoom_shouldClampToMinAndMax() {
        DashboardViewModel vm = new DashboardViewModel();

        vm.setZoom(99.0);
        assertThat(vm.getZoomScale()).isEqualTo(DashboardViewModel.getMaxZoom());

        vm.setZoom(0.01);
        assertThat(vm.getZoomScale()).isEqualTo(DashboardViewModel.getMinZoom());
    }

    @Test
    void toggleAndSetState_shouldUpdateFlags() {
        DashboardViewModel vm = new DashboardViewModel();

        vm.setSnapToGrid(true);
        vm.toggleSnapToGrid();
        assertThat(vm.isSnapToGrid()).isFalse();

        vm.setLayoutLocked(false);
        vm.toggleLayoutLocked();
        assertThat(vm.isLayoutLocked()).isTrue();

        vm.setEditMode(false);
        vm.toggleEditMode();
        assertThat(vm.isEditMode()).isTrue();
    }

    @Test
    void snapPoint_shouldRespectSnapSetting() {
        DashboardViewModel vm = new DashboardViewModel();

        vm.setSnapToGrid(true);
        Point2D snapped = vm.snapPoint(11.0, 29.0);
        assertThat(snapped.getX()).isEqualTo(20.0);
        assertThat(snapped.getY()).isEqualTo(20.0);

        vm.setSnapToGrid(false);
        Point2D raw = vm.snapPoint(11.0, 29.0);
        assertThat(raw.getX()).isEqualTo(11.0);
        assertThat(raw.getY()).isEqualTo(29.0);
    }

    @Test
    void preferences_shouldBeLoadedByNewInstance() {
        DashboardViewModel first = new DashboardViewModel();
        first.setZoom(1.7);
        first.setSnapToGrid(false);
        first.setLayoutLocked(true);
        first.setDashboardOnlyMode(true);

        DashboardViewModel second = new DashboardViewModel();
        assertThat(second.getZoomScale()).isEqualTo(1.7);
        assertThat(second.isSnapToGrid()).isFalse();
        assertThat(second.isLayoutLocked()).isTrue();
        assertThat(second.isDashboardOnlyMode()).isTrue();
    }
}
