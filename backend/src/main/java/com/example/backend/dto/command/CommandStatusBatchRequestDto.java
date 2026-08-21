package com.example.backend.dto.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CommandStatusBatchRequestDto {

    @NotEmpty(message = "taskIds는 필수입니다.")
    @Size(max = 2000, message = "taskIds는 최대 2000개까지 조회할 수 있습니다.")
    private List<@Positive(message = "taskId는 1 이상이어야 합니다.") Long> taskIds;

    public List<Long> getTaskIds() {
        return taskIds;
    }

    public void setTaskIds(List<Long> taskIds) {
        this.taskIds = taskIds;
    }
}
