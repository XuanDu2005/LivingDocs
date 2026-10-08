package com.livingdocs.modules.admin.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.BroadcastHistoryEntry;
import com.livingdocs.modules.admin.dto.SendNotificationRequest;
import com.livingdocs.modules.admin.dto.SendNotificationResponse;
import com.livingdocs.modules.admin.service.AdminNotificationService;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Administrator endpoints for sending platform-wide notifications.
 *
 * <p>All endpoints require the {@code ADMIN} platform role. Recipients
 * are resolved server-side — the client only declares the target
 * shape (single user, role, all enabled).
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · Notifications", description = "Broadcast notifications to platform users")
public class AdminNotificationController {

    private static final int HISTORY_LIMIT = 50;
    /** Audit log action prefix used by AdminNotificationService. */
    private static final String ACTION_NOTIFICATION_SEND = "notification.";

    private final AdminNotificationService sendService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AdminNotificationController(AdminNotificationService sendService,
                                       AuditLogRepository auditLogRepository,
                                       ObjectMapper objectMapper) {
        this.sendService = sendService;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/send")
    @Operation(summary = "Send a notification to a user, role, or all enabled users")
    public SendNotificationResponse send(@Valid @RequestBody SendNotificationRequest req) {
        return sendService.send(CurrentUser.requireId(), req);
    }

    @GetMapping("/history")
    @Operation(summary = "List past broadcast campaigns (one row per admin send, not per recipient)")
    public List<BroadcastHistoryEntry> history(
            @RequestParam(defaultValue = "50") int limit) {
        int clamped = Math.min(Math.max(limit, 1), HISTORY_LIMIT);
        List<AuditLog> rows = auditLogRepository
                .findAllByActionStartingWithOrderByCreatedAtDesc(ACTION_NOTIFICATION_SEND);
        List<BroadcastHistoryEntry> out = new ArrayList<>(rows.size());
        for (AuditLog row : rows) {
            if (out.size() >= clamped) break;
            Map<String, Object> payload = parse(row.getPayload());
            out.add(new BroadcastHistoryEntry(
                    row.getId(),
                    row.getActorUserId(),
                    row.getActorRole(),
                    row.getCreatedAt(),
                    row.getAction(),
                    stringOf(payload, "target"),
                    stringOf(payload, "roleCode"),
                    stringOf(payload, "kind"),
                    stringOf(payload, "title"),
                    intOf(payload, "recipients"),
                    stringOf(payload, "body"),
                    stringOf(payload, "link")));
        }
        return out;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stringOf(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static int intOf(Map<String, Object> m, String key) {
        if (m == null) return 0;
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}