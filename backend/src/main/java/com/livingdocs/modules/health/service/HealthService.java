package com.livingdocs.modules.health.service;

import com.livingdocs.modules.health.dto.HealthResponse;
import org.springframework.stereotype.Service;

/**
 * Encapsulates the trivial "is the service alive?" logic.
 *
 * <p>Kept as a service to demonstrate the controller → service → dto split
 * and to give future health checks (DB ping, downstream pings) a place to grow
 * without modifying the controller.
 */
@Service
public class HealthService {

    public HealthResponse check() {
        return HealthResponse.up();
    }
}