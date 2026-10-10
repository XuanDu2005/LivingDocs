package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.PlatformDocTypePolicyResponse;
import com.livingdocs.modules.admin.dto.PlatformSettingsResponse;
import com.livingdocs.modules.admin.dto.UpdatePlatformDocTypePolicyRequest;
import com.livingdocs.modules.admin.dto.UpdatePlatformSettingsRequest;
import com.livingdocs.modules.admin.service.PlatformSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator endpoints that govern the platform-wide defaults for
 * documentation generation and merge policies.
 *
 * <p>These are the values copied into a new workspace's
 * {@code workspace_settings} on creation. Existing workspaces keep
 * their per-workspace values.
 */
@RestController
@RequestMapping("/api/v1/admin/platform-settings")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin Platform Settings",
        description = "Platform-wide defaults for documentation governance")
public class AdminPlatformSettingsController {

    private final PlatformSettingsService service;

    public AdminPlatformSettingsController(PlatformSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get the platform-wide default governance settings")
    public PlatformSettingsResponse get() {
        return service.getSettings();
    }

    @PutMapping
    @Operation(summary = "Update the platform-wide default governance settings")
    public PlatformSettingsResponse update(@Valid @RequestBody UpdatePlatformSettingsRequest req) {
        return service.updateSettings(req);
    }

    @GetMapping("/doc-type-policies")
    @Operation(summary = "List the per-docType workflow policies (auto-apply vs review)")
    public List<PlatformDocTypePolicyResponse> listDocTypePolicies() {
        return service.listDocTypePolicies();
    }

    @PutMapping("/doc-type-policies/{docType}")
    @Operation(summary = "Upsert the workflow policy for a single doc type")
    public PlatformDocTypePolicyResponse upsertDocTypePolicy(
            @PathVariable String docType,
            @Valid @RequestBody UpdatePlatformDocTypePolicyRequest req) {
        return service.upsertDocTypePolicy(docType, req);
    }

    @DeleteMapping("/doc-type-policies/{docType}")
    @Operation(summary = "Delete the workflow policy for a single doc type (falls back to MANAGER_REVIEW)")
    public org.springframework.http.ResponseEntity<Void> deleteDocTypePolicy(@PathVariable String docType) {
        service.deleteDocTypePolicy(docType);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
