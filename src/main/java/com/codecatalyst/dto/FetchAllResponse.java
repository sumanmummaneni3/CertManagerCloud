package com.codecatalyst.dto;

import com.codecatalyst.service.CertificateFetchService.FetchResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Summary response when fetching certificates for all targets in an org.
 */
@Data
@Builder
public class FetchAllResponse {
    private int    totalTargets;
    private int    succeeded;
    private int    failed;
    private List<FetchResult> results;
}
