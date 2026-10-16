package com.livingdocs.modules.docsync.dto;

import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @Size(max = 4000) String comment
) {}
