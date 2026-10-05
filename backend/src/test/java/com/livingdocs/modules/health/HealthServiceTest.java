package com.livingdocs.modules.health;

import com.livingdocs.common.AppConstants;
import com.livingdocs.modules.health.dto.HealthResponse;
import com.livingdocs.modules.health.service.HealthService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure unit test of the health service — no Spring context required.
 * Demonstrates that the controller → service → dto chain is intact.
 */
class HealthServiceTest {

    private final HealthService healthService = new HealthService();

    @Test
    void check_returnsUpStatus() {
        HealthResponse response = healthService.check();

        assertNotNull(response);
        assertEquals("UP", response.status());
        assertEquals(AppConstants.SERVICE_NAME, response.service());
    }
}