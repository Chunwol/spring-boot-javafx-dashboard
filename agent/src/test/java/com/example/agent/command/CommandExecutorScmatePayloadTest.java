package com.example.agent.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agent.scmate.ScmateController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorScmatePayloadTest {

    @Test
    void objectPayloadIsSerializedWithoutDroppingRecoveryPointOrOtherFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree("""
                {"action":"REQUEST_PROTECTED","recoveryPoint":"SNAP2","requestId":"abc"}
                """);

        JsonNode forwarded = mapper.readTree(CommandExecutor.serializePayload(payload));

        assertThat(forwarded).isEqualTo(payload);
        assertThat(forwarded.path("recoveryPoint").asText()).isEqualTo("SNAP2");
        assertThat(forwarded.path("requestId").asText()).isEqualTo("abc");
    }

    @Test
    void successfulModeRequestProvidesConfirmedHeartbeatStatus() {
        ObjectMapper mapper = new ObjectMapper();
        ScmateController.Result result = new ScmateController.Result(0, """
                {"after":{"available":true,"mode":"OPERATOR","transitionPending":false,
                "raw":3,"schedule":"MANUAL","selectedPoint":"BASELINE",
                "availabilityReason":"ACTIVE"}}
                """, "");

        var confirmed = CommandExecutor.confirmedScmateStatus("REQUEST_OPERATOR", result, mapper);

        assertThat(confirmed)
                .containsEntry("scmateAvailable", true)
                .containsEntry("scmateMode", "OPERATOR")
                .containsEntry("scmateRawState", 3L)
                .containsEntry("scmateAvailabilityReason", "ACTIVE");
    }

    @Test
    void failedOrReadOnlyScmateResultIsNotConfirmed() {
        ObjectMapper mapper = new ObjectMapper();
        ScmateController.Result success = new ScmateController.Result(0,
                "{\"after\":{\"mode\":\"OPERATOR\"}}", "");
        ScmateController.Result failure = new ScmateController.Result(1, "", "failed");

        assertThat(CommandExecutor.confirmedScmateStatus("STATUS", success, mapper)).isEmpty();
        assertThat(CommandExecutor.confirmedScmateStatus("REQUEST_OPERATOR", failure, mapper)).isEmpty();
    }
}
