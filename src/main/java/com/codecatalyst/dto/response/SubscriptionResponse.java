package com.codecatalyst.dto.response;

import com.codecatalyst.enums.SubscriptionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class SubscriptionResponse {
    private UUID id;
    private UUID orgId;
    private String orgName;
    private Integer maxTargets;
    private long currentTargets;
    private SubscriptionStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
