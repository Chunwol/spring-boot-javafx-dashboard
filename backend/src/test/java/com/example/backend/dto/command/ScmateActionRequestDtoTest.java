package com.example.backend.dto.command;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScmateActionRequestDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void missingRecoveryPoint_defaultsToBaseline() {
        ScmateActionRequestDto request = new ScmateActionRequestDto(
                "AA:BB:CC:DD:EE:FF", "REFRESH_STATUS_DETAIL", null);

        assertThat(request.recoveryPoint()).isEqualTo("BASELINE");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void recoveryPoint_acceptsOnlyNamedPoints() {
        ScmateActionRequestDto request = new ScmateActionRequestDto(
                "AA:BB:CC:DD:EE:FF", "UPDATE_BASELINE", "LATEST");

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("recoveryPoint"));
    }
}
