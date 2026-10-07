package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssignRolesRequest(
        @NotEmpty(message = "roles must not be empty")
        List<String> roles
) {}