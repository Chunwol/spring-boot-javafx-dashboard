package com.example.frontend.controller;

import com.example.frontend.controller.dashboard.DashboardAlertApiClient;
import com.example.frontend.controller.dashboard.DashboardAlertCoordinator;
import com.example.frontend.service.ApiClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardControllerAlertDependencyTest {

    @Test
    void constructor_shouldInitializeAlertDependenciesWithDefaultsWhenNull() throws Exception {
        DashboardController controller = new DashboardController(null, null);

        Field rawApiClientField = DashboardController.class.getDeclaredField("apiClient");
        rawApiClientField.setAccessible(true);
        Object rawApiClient = rawApiClientField.get(controller);

        Field apiField = DashboardController.class.getDeclaredField("dashboardAlertApiClient");
        apiField.setAccessible(true);
        Object apiValue = apiField.get(controller);

        Field coordinatorField = DashboardController.class.getDeclaredField("alertCoordinator");
        coordinatorField.setAccessible(true);
        Object coordinatorValue = coordinatorField.get(controller);

        assertThat(rawApiClient).isInstanceOf(ApiClient.class);
        assertThat(apiValue).isInstanceOf(DashboardAlertApiClient.class);
        assertThat(coordinatorValue).isInstanceOf(DashboardAlertCoordinator.class);
    }

    @Test
    void constructor_shouldUseProvidedAlertDependencies() throws Exception {
        DashboardAlertApiClient providedApi = new DashboardAlertApiClient(new ApiClient("http://localhost:8888/api/v1"));
        DashboardAlertCoordinator providedCoordinator = new DashboardAlertCoordinator();

        DashboardController controller = new DashboardController(providedApi, providedCoordinator);

        Field apiField = DashboardController.class.getDeclaredField("dashboardAlertApiClient");
        apiField.setAccessible(true);
        Object apiValue = apiField.get(controller);

        Field coordinatorField = DashboardController.class.getDeclaredField("alertCoordinator");
        coordinatorField.setAccessible(true);
        Object coordinatorValue = coordinatorField.get(controller);

        assertThat(apiValue).isSameAs(providedApi);
        assertThat(coordinatorValue).isSameAs(providedCoordinator);
    }

    @Test
    void constructor_shouldUseProvidedRawApiClientWhenExplicitlyInjected() throws Exception {
        ApiClient rawApiClient = new ApiClient("http://localhost:8888/api/v1");
        DashboardController controller = new DashboardController(rawApiClient, null, null);

        Field rawApiClientField = DashboardController.class.getDeclaredField("apiClient");
        rawApiClientField.setAccessible(true);
        Object rawApiValue = rawApiClientField.get(controller);

        assertThat(rawApiValue).isSameAs(rawApiClient);
    }
}
