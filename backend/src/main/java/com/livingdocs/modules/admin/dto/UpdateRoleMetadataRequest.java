package com.livingdocs.modules.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Body for editing the metadata of a platform role.
 *
 * <p>Only the presentation of a role is editable: its
 * {@code code} is the identifier that JWTs, audit payloads and
 * permission grants refer to, so changing it would invalidate every
 * assignment that points at the role. New role codes therefore need a
 * migration, not an API call.
 */
public record UpdateRoleMetadataRequest(
        @Size(max = 120) String name,
        @Size(max = 500) String description,
        @Min(0) @Max(9999) Integer displayOrder
) {}
