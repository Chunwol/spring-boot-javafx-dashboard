package com.example.backend.service.alert;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.alert.OperationalAlert;
import com.example.backend.repository.alert.OperationalAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertCenterServiceTest {

    @Mock
    OperationalAlertRepository operationalAlertRepository;

    @InjectMocks
    AlertCenterService alertCenterService;

    @Test
    void create_shouldNormalizeAndPersistFields() {
        AlertEvent event = new AlertEvent(this, "", "queue backlog high", "critical", "ops");
        when(operationalAlertRepository.save(any(OperationalAlert.class)))
                .thenAnswer(invocation -> {
                    OperationalAlert alert = invocation.getArgument(0);
                    alert.setId(10L);
                    return alert;
                });

        OperationalAlert saved = alertCenterService.create(event);

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getSourceMac()).isEqualTo("SYSTEM");
        assertThat(saved.getSeverity()).isEqualTo("CRITICAL");
        assertThat(saved.getAlertType()).isEqualTo("OPS");
        assertThat(saved.getAcknowledged()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void acknowledge_shouldMarkAlertAsAcknowledged() {
        OperationalAlert alert = new OperationalAlert();
        alert.setId(99L);
        alert.setAcknowledged(false);
        alert.setCreatedAt(LocalDateTime.now());
        when(operationalAlertRepository.findById(99L)).thenReturn(Optional.of(alert));

        alertCenterService.acknowledge(99L, "admin");

        ArgumentCaptor<OperationalAlert> captor = ArgumentCaptor.forClass(OperationalAlert.class);
        verify(operationalAlertRepository).save(captor.capture());
        OperationalAlert saved = captor.getValue();
        assertThat(saved.getAcknowledged()).isTrue();
        assertThat(saved.getAcknowledgedBy()).isEqualTo("admin");
        assertThat(saved.getAcknowledgedAt()).isNotNull();
    }
}
