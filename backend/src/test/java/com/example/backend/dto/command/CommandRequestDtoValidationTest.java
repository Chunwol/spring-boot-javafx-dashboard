package com.example.backend.dto.command;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRequestDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsScmateControlForExplicitMultiTargetQueueing() {
        CommandRequestDto request = new CommandRequestDto();
        request.setScope("MULTI");
        request.setRole("STUDENT");
        request.setType("SCMATE_CONTROL");
        request.setTargetMacs(List.of("AA:BB:CC:DD:EE:FF"));
        request.setPayloadJson("{\"action\":\"REQUEST_OPERATOR\",\"recoveryPoint\":\"BASELINE\"}");

        assertThat(validator.validate(request)).isEmpty();
    }
}
