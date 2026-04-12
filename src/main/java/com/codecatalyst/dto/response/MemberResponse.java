package com.codecatalyst.dto.response;

import com.codecatalyst.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class MemberResponse {
    private UUID id;
    private String email;
    private String name;
    private UserRole role;
    private Instant createdAt;
}
