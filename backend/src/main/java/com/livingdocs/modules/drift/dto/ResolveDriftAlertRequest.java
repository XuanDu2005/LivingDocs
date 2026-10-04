package com.livingdocs.modules.drift.dto;

import com.livingdocs.modules.drift.model.DriftResolution;

/**
 * Body for closing / resolving a drift alert.
 */
public record ResolveDriftAlertRequest(
        DriftResolution resolution
) {}