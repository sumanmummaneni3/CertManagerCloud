package com.codecatalyst.dto.request;
import lombok.Data;
@Data
public class UpdateTargetRequest {
    private String host;
    private Integer port;
    private Boolean isPrivate;
    private Boolean enabled;
    private String description;
}
