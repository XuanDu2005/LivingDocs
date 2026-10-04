package com.livingdocs.modules.health.dto;

import com.livingdocs.common.AppConstants;

/**
 * Response payload for the health endpoint.
 *
 * <p>This is a record so that the contract is immutable and trivially
 * serialisable by Jackson with no additional configuration.
 */
public record HealthResponse(String status, String service) {

    public static HealthResponse up() {
        return new HealthResponse("UP", AppConstants.SERVICE_NAME);
    }
}