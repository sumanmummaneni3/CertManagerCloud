package com.codecatalyst.dto.response;

import com.codecatalyst.enums.ScanJobStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class ScanStatusResponse {
    private UUID jobId;
    private UUID targetId;
    private ScanJobStatus status;
    private String resultType;
    private String errorMsg;
    private Instant createdAt;
    private Instant claimedAt;
    private Instant completedAt;
}
