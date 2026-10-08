package com.livingdocs.modules.notification.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.notification.dto.NotificationPolicyDto;
import com.livingdocs.modules.notification.model.NotificationKinds;
import com.livingdocs.modules.notification.model.NotificationPolicy;
import com.livingdocs.modules.notification.repository.NotificationPolicyRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read / write per-{@link com.livingdocs.modules.notification.model.NotificationPolicy}
 * toggles. Resolution order is per-workspace row first, then the
 * platform default row (workspace_id = null); when neither exists the
 * kind is treated as enabled (the legacy default).
 *
 * <p>Mutations require MANAGER-or-above for a workspace row, and the
 * ADMIN platform role for the platform default row. Every change is
 * recorded in the audit log.
 */
@Service
public class NotificationPolicyService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPolicyService.class);

    private final NotificationPolicyRepository repository;
    private final AuditLogService auditLogService;
    private final WorkspaceService workspaceService;

    public NotificationPolicyService(NotificationPolicyRepository repository,
                                     AuditLogService auditLogService,
                                     WorkspaceService workspaceService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.workspaceService = workspaceService;
    }

    // ---- Resolver ---------------------------------------------------------

    /**
     * Is the given kind enabled for this workspace?
     *
     * <ol>
     *   <li>workspaceId + eventKind -&gt; row's enabled flag.</li>
     *   <li>Workspace-id is null + eventKind (platform default) -&gt; row's flag.</li>
     *   <li>Otherwise &rarr; true.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID workspaceId, String eventKind) {
        if (eventKind == null || eventKind.isBlank()) return true;
        if (workspaceId != null) {
            Optional<NotificationPolicy> ws = repository.findByWorkspaceIdAndEventKind(workspaceId, eventKind);
            if (ws.isPresent()) return ws.get().isEnabled();
        }
        Optional<NotificationPolicy> global = repository
                .findByWorkspaceIdAndEventKind(null, eventKind);
        return global.map(NotificationPolicy::isEnabled).orElse(true);
    }

    // ---- Listing ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NotificationPolicyDto> listPlatformDefaults() {
        Map<String, Boolean> rows = repository.findAllByWorkspaceIdIsNullOrderByEventKindAsc().stream()
                .collect(Collectors.toMap(NotificationPolicy::getEventKind,
                        NotificationPolicy::isEnabled));
        List<NotificationPolicyDto> out = new ArrayList<>();
        for (String kind : NotificationKinds.ALL) {
            out.add(NotificationPolicyDto.of(kind,
                    rows.getOrDefault(kind, true),
                    "PLATFORM"));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<NotificationPolicyDto> listForWorkspace(UUID workspaceId) {
        Map<String, Boolean> workspaceRows = repository
                .findAllByWorkspaceIdOrderByEventKindAsc(workspaceId).stream()
                .collect(Collectors.toMap(NotificationPolicy::getEventKind,
                        NotificationPolicy::isEnabled));
        Map<String, Boolean> platformRows = repository
                .findAllByWorkspaceIdIsNullOrderByEventKindAsc().stream()
                .collect(Collectors.toMap(NotificationPolicy::getEventKind,
                        NotificationPolicy::isEnabled));
        List<NotificationPolicyDto> out = new ArrayList<>();
        for (String kind : NotificationKinds.ALL) {
            // Per-workspace flag wins; otherwise inherit the platform default.
            boolean enabled;
            String scope;
            if (workspaceRows.containsKey(kind)) {
                enabled = workspaceRows.get(kind);
                scope = "WORKSPACE";
            } else if (platformRows.containsKey(kind)) {
                enabled = platformRows.get(kind);
                scope = "INHERITED";
            } else {
                enabled = true;
                scope = "DEFAULT";
            }
            out.add(NotificationPolicyDto.of(kind, enabled, scope));
        }
        return out;
    }

    // ---- Mutating ---------------------------------------------------------

    @Transactional
    public NotificationPolicyDto upsertPlatformDefault(UUID actorId, String actorRoleCode,
                                                        String eventKind, boolean enabled) {
        ensureValidKind(eventKind);
        NotificationPolicy existing = repository.findByWorkspaceIdAndEventKind(null, eventKind)
                .orElse(null);
        boolean previous = existing == null ? true : existing.isEnabled();
        NotificationPolicy row;
        if (existing == null) {
            row = NotificationPolicy.of(null, eventKind, enabled, actorId);
        } else {
            existing.applyChange(enabled, actorId);
            row = existing;
        }
        row = repository.save(row);

        auditLogService.record(actorId, actorRoleCode,
                "notification.policy.update",
                "notification_policy",
                row.getId().toString(), null,
                Map.of("scope", "PLATFORM",
                        "eventKind", eventKind,
                        "previous", previous,
                        "enabled", enabled));
        log.info("Platform notification policy {} = {} (actor={})", eventKind, enabled, actorId);
        return NotificationPolicyDto.of(eventKind, enabled, "PLATFORM");
    }

    @Transactional
    public NotificationPolicyDto upsertWorkspaceOverride(UUID actorId, UUID workspaceId,
                                                          String eventKind, boolean enabled) {
        ensureValidKind(eventKind);
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        NotificationPolicy existing = repository
                .findByWorkspaceIdAndEventKind(workspaceId, eventKind)
                .orElse(null);
        boolean previous;
        NotificationPolicy row;
        if (existing == null) {
            previous = isEnabled(workspaceId, eventKind);
            row = NotificationPolicy.of(workspaceId, eventKind, enabled, actorId);
        } else {
            previous = existing.isEnabled();
            existing.applyChange(enabled, actorId);
            row = existing;
        }
        row = repository.save(row);

        auditLogService.record(actorId, "MANAGER",
                "notification.policy.update",
                "notification_policy",
                row.getId().toString(), workspaceId,
                Map.of("scope", "WORKSPACE",
                        "workspaceId", workspaceId.toString(),
                        "eventKind", eventKind,
                        "previous", previous,
                        "enabled", enabled));
        log.info("Workspace {} notification policy {} = {} (actor={})",
                workspaceId, eventKind, enabled, actorId);
        return NotificationPolicyDto.of(eventKind, enabled, "WORKSPACE");
    }

    private static void ensureValidKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("Event kind is required");
        }
        if (!NotificationKinds.ALL.contains(kind)) {
            throw new BadRequestException("Unknown notification event kind: " + kind);
        }
    }
}