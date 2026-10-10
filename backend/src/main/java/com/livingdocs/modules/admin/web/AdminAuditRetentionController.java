package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.AuditRetentionPolicyResponse;
import com.livingdocs.modules.admin.dto.UpdateRetentionPolicyRequest;
import com.livingdocs.modules.admin.service.AuditRetentionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator endpoints for the audit log retention policy catalogue.
 * All endpoints require the {@code ADMIN} platform role.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-retention")
@RequirePlatformRole({"ADMIN"})
public class AdminAuditRetentionController {

    private final AuditRetentionService service;

    public AdminAuditRetentionController(AuditRetentionService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditRetentionPolicyResponse> list() {
        return service.listPolicies();
    }

    @PutMapping
    public AuditRetentionPolicyResponse upsert(@Valid @RequestBody UpdateRetentionPolicyRequest req) {
        return service.upsert(CurrentUser.requireId(), req);
    }

    @DeleteMapping("/{entityType}")
    public ResponseEntity<Void> delete(@PathVariable String entityType) {
        service.deletePolicy(CurrentUser.requireId(), entityType);
        return ResponseEntity.noContent().build();
    }

    /**
     * Manually trigger the pruning job. Returns the number of records
     * pruned across all enabled policies.
     */
    @PostMapping("/run")
    public AuditRetentionService.PruneResult runPrune() {
        return service.pruneAll();
    }
}
