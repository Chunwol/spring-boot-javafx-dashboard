package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardDataServiceTest {

    @Test
    void fetchClassrooms_shouldReturnClientPayload() throws Exception {
        List<ClassroomSummaryDto> payload = List.of(new ClassroomSummaryDto());
        DashboardDataService service = new DashboardDataService(new StubApiClient(payload, null));

        List<ClassroomSummaryDto> result = service.fetchClassrooms();

        assertThat(result).hasSize(1);
    }

    @Test
    void fetchRefreshSnapshotAsync_shouldFallbackToEmptyDtoOnFailure() throws InterruptedException {
        DashboardDataService service = new DashboardDataService(new StubApiClient(null, new RuntimeException("boom")));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DashboardRefreshDto> observed = new AtomicReference<>();

        service.fetchRefreshSnapshotAsync(dto -> {
            observed.set(dto);
            latch.countDown();
        });

        boolean completed = latch.await(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().getClassrooms()).isNotNull().isEmpty();
        assertThat(observed.get().getTraffic()).isNotNull();
    }

    private static class StubApiClient extends ApiClient {
        private final List<ClassroomSummaryDto> classrooms;
        private final RuntimeException refreshFailure;

        StubApiClient(List<ClassroomSummaryDto> classrooms, RuntimeException refreshFailure) {
            super("http://localhost:0");
            this.classrooms = classrooms;
            this.refreshFailure = refreshFailure;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> getJson(String path, TypeReference<T> typeRef) {
            if ("/dashboard/refresh".equals(path)) {
                if (refreshFailure != null) {
                    return CompletableFuture.failedFuture(refreshFailure);
                }
                return CompletableFuture.completedFuture((T) new DashboardRefreshDto());
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unexpected path: " + path));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> java.util.concurrent.CompletableFuture<java.util.List<T>> getPagedList(String path, TypeReference<java.util.List<T>> typeRef) {
            if ("/dashboard/classrooms".equals(path)) {
                return CompletableFuture.completedFuture((java.util.List<T>) classrooms);
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unexpected path: " + path));
        }
    }
}
