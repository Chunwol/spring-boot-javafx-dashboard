package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardAlertApiClientTest {

    @Test
    void fetchAlertSnapshot_shouldClampLimitToOne() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        DashboardAlertItem item = new DashboardAlertItem();
        item.setId(1L);
        fakeApiClient.getJsonPayload = List.of(item);

        DashboardAlertApiClient client = new DashboardAlertApiClient(fakeApiClient);
        List<DashboardAlertItem> result = client.fetchAlertSnapshot(0).join();

        assertThat(fakeApiClient.lastPath).isEqualTo("/alerts?limit=1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void fetchUnreadCount_shouldNormalizeValue() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        DashboardAlertApiClient client = new DashboardAlertApiClient(fakeApiClient);

        fakeApiClient.getJsonPayload = Map.of("unread", 12);
        assertThat(client.fetchUnreadCount().join()).isEqualTo(12L);

        fakeApiClient.getJsonPayload = Map.of("unread", -7);
        assertThat(client.fetchUnreadCount().join()).isEqualTo(0L);

        fakeApiClient.getJsonPayload = Map.of();
        assertThat(client.fetchUnreadCount().join()).isEqualTo(0L);
    }

    @Test
    void acknowledgeAlert_shouldCallAckEndpoint() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        DashboardAlertApiClient client = new DashboardAlertApiClient(fakeApiClient);

        client.acknowledgeAlert(77L).join();

        assertThat(fakeApiClient.lastPath).isEqualTo("/alerts/77/ack");
        assertThat(fakeApiClient.lastPostBody).isEqualTo(Map.of());
    }

    @Test
    void acknowledgeAlert_whenIdIsNull_shouldFailFast() {
        DashboardAlertApiClient client = new DashboardAlertApiClient(new FakeApiClient());

        assertThatThrownBy(() -> client.acknowledgeAlert(null).join())
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alertId");
    }

    @Test
    void acknowledgeAllAlerts_shouldCallAckAllEndpoint() {
        FakeApiClient fakeApiClient = new FakeApiClient();
        DashboardAlertApiClient client = new DashboardAlertApiClient(fakeApiClient);

        client.acknowledgeAllAlerts().join();

        assertThat(fakeApiClient.lastPath).isEqualTo("/alerts/ack-all");
        assertThat(fakeApiClient.lastPostBody).isEqualTo(Map.of());
    }

    private static final class FakeApiClient extends ApiClient {

        private Object getJsonPayload;
        private Throwable getJsonError;
        private Object lastPostBody;
        private String lastPath;

        private FakeApiClient() {
            super("http://localhost:8888/api/v1");
        }

        @Override
        public <T> CompletableFuture<T> getJson(String path, TypeReference<T> typeRef) {
            this.lastPath = path;
            if (getJsonError != null) {
                return failed(getJsonError);
            }
            @SuppressWarnings("unchecked")
            T payload = (T) getJsonPayload;
            return CompletableFuture.completedFuture(payload);
        }

        @Override
        public CompletableFuture<String> postJson(String path, Object body) {
            this.lastPath = path;
            this.lastPostBody = body;
            return CompletableFuture.completedFuture("{}");
        }

        private static <T> CompletableFuture<T> failed(Throwable error) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(error);
            return future;
        }
    }
}
