package com.codecatalyst.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.UUID;
@Data
public class CreateTargetRequest {
    @NotBlank @Size(max = 255)
    private String host;
    @Min(1) @Max(65535)
    private int port = 443;
    private boolean isPrivate = false;
    private UUID agentId;
    @Size(max = 255)
    private String description;
}
