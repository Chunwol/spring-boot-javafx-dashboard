package com.example.backend.dto.user;

import com.example.backend.model.user.FeaturePermission;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class UserPermissionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsEveryDetailedPermissionInOneUpdate() {
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        ReflectionTestUtils.setField(request, "permissions",
                Arrays.stream(FeaturePermission.values()).map(Enum::name).toList());

        assertThat(validator.validate(request)).isEmpty();
    }
}
