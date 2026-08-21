package com.example.backend.service.setting;

import com.example.backend.model.UserSetting;
import com.example.backend.repository.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingServiceTest {

    @Mock
    private UserSettingRepository repository;

    private UserSettingService service;

    @BeforeEach
    void setUp() {
        service = new UserSettingService(repository);
    }

    @Test
    void scopesSettingsByNormalizedUsername() {
        when(repository.findById("viewer:alert-type-filter"))
                .thenReturn(Optional.of(new UserSetting("viewer:alert-type-filter", "[\"PC_OFFLINE\"]")));

        assertThat(service.get(" Viewer ", "alert-type-filter")).isEqualTo("[\"PC_OFFLINE\"]");
    }

    @Test
    void savesDifferentUsersUnderDifferentKeys() {
        when(repository.findById("viewer:alert-type-filter")).thenReturn(Optional.empty());
        when(repository.findById("operator:alert-type-filter")).thenReturn(Optional.empty());

        service.save("viewer", "alert-type-filter", "viewer-value");
        service.save("operator", "alert-type-filter", "operator-value");

        ArgumentCaptor<UserSetting> captor = ArgumentCaptor.forClass(UserSetting.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(UserSetting::getKey, UserSetting::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("viewer:alert-type-filter", "viewer-value"),
                        org.assertj.core.groups.Tuple.tuple("operator:alert-type-filter", "operator-value"));
    }

    @Test
    void rejectsMissingIdentityOrKey() {
        assertThatThrownBy(() -> service.get(" ", "theme"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("viewer", " ", "dark"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
