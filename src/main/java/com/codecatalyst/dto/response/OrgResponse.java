package com.codecatalyst.dto.response;

import com.codecatalyst.enums.SubscriptionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class OrgResponse {
    private UUID id;
    private String name;
    private String slug;
    private int maxTargets;
    private SubscriptionStatus status;
    private Instant createdAt;
}
