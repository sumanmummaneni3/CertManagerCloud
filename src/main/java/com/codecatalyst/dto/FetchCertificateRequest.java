package com.codecatalyst.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for any operation that requires opening the org keystore.
 * The password is supplied per-request and never persisted.
 */
@Data
public class FetchCertificateRequest {

    @NotBlank(message = "Keystore password is required")
    private String keystorePassword;
}
