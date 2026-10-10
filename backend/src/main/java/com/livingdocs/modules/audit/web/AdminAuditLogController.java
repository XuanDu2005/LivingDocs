package com.livingdocs.modules.audit.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only cross-workspace audit log viewer with export capability.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin Audit Log", description = "Cross-workspace audit log with export")
public class AdminAuditLogController {

    private final AuditLogRepository repository;

    public AdminAuditLogController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List audit logs across all workspaces (admin only)")
    public ResponseEntity<PageResponse> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID workspaceId) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 500),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AuditLog> all = repository.findAll(pageable).getContent();
        // Filter in-memory for now (Postgres can do this with JPA Specifications)
        var filtered = all.stream()
                .filter((l) -> action == null || l.getAction().contains(action))
                .filter((l) -> actorId == null || actorId.equals(l.getActorUserId()))
                .filter((l) -> workspaceId == null || workspaceId.equals(l.getWorkspaceId()))
                .toList();
        return ResponseEntity.ok(new PageResponse(filtered, page, size, (long) filtered.size()));
    }

    @GetMapping("/export")
    @Operation(summary = "Export audit logs to CSV")
    public void exportCsv(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(defaultValue = "csv") String format,
            HttpServletResponse response) throws IOException {
        Pageable pageable = PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AuditLog> all = repository.findAll(pageable).getContent();
        var filtered = all.stream()
                .filter((l) -> action == null || l.getAction().contains(action))
                .filter((l) -> actorId == null || actorId.equals(l.getActorUserId()))
                .filter((l) -> workspaceId == null || workspaceId.equals(l.getWorkspaceId()))
                .toList();

        if ("json".equalsIgnoreCase(format)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Content-Disposition", "attachment; filename=audit-logs.json");
            try (PrintWriter w = response.getWriter()) {
                w.println("[");
                for (int i = 0; i < filtered.size(); i++) {
                    AuditLog l = filtered.get(i);
                    w.print(String.format(
                            "  {\"id\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"action\":\"%s\"," +
                            "\"resourceType\":\"%s\",\"resourceId\":\"%s\",\"workspaceId\":%s," +
                            "\"createdAt\":\"%s\",\"payload\":%s}",
                            l.getId(),
                            l.getActorUserId(),
                            l.getActorRole(),
                            l.getAction().replace("\"", "\\\""),
                            l.getResourceType(),
                            l.getResourceId() != null ? l.getResourceId().replace("\"", "\\\"") : "",
                            l.getWorkspaceId() != null ? "\"" + l.getWorkspaceId() + "\"" : "null",
                            l.getCreatedAt() != null ? l.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : "",
                            l.getPayload() != null ? l.getPayload() : "{}"
                    ));
                    if (i < filtered.size() - 1) w.println(",");
                }
                w.println("\n]");
            }
        } else {
            // CSV
            response.setContentType("text/csv");
            response.setHeader("Content-Disposition", "attachment; filename=audit-logs.csv");
            try (PrintWriter w = response.getWriter()) {
                w.println("id,actor_id,actor_role,action,resource_type,resource_id,workspace_id,created_at,payload");
                for (AuditLog l : filtered) {
                    w.print(l.getId());
                    w.print(",");
                    w.print(l.getActorUserId() != null ? l.getActorUserId() : "");
                    w.print(",");
                    w.print(l.getActorRole() != null ? l.getActorRole() : "");
                    w.print(",");
                    w.print(escapeCsv(l.getAction()));
                    w.print(",");
                    w.print(escapeCsv(l.getResourceType()));
                    w.print(",");
                    w.print(escapeCsv(l.getResourceId()));
                    w.print(",");
                    w.print(l.getWorkspaceId() != null ? l.getWorkspaceId() : "");
                    w.print(",");
                    w.print(l.getCreatedAt() != null ? l.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : "");
                    w.print(",");
                    w.println(escapeCsv(l.getPayload()));
                }
            }
        }
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public record PageResponse(List<AuditLog> items, int page, int size, long total) {}
}
