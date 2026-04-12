package com.codecatalyst.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateQuotaRequest {

    @NotNull(message = "maxTargets is required")
    @Min(value = 1, message = "maxTargets must be at least 1")
    @Max(value = 10000, message = "maxTargets cannot exceed 10000")
    private Integer maxTargets;
}
