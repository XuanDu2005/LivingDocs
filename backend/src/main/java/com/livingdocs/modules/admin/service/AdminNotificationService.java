package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.SendNotificationRequest;
import com.livingdocs.modules.admin.dto.SendNotificationResponse;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Administrator-only fan-out for in-app notifications.
 *
 * <p>Targets are resolved into a concrete set of {@code userId}s, then
 * delegated to {@link NotificationService#sendBatch}. The whole batch is
 * recorded as a single audit-log entry so administrators can later
 * reconstruct "who sent what to whom when" without scanning N rows.
 */
@Service
public class AdminNotificationService {

    private static final String KIND_ADMIN = "ADMIN_BROADCAST";

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AdminNotificationService(
            NotificationService notificationService,
            UserRepository userRepository,
            AuditLogService auditLogService) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public SendNotificationResponse send(UUID actorId, SendNotificationRequest req) {
        String target = req.target() == null ? "" : req.target().toLowerCase();
        List<UUID> resolved;
        switch (target) {
            case "user" -> {
                if (req.userId() == null) {
                    throw new BadRequestException("userId is required when target=user");
                }
                User u = userRepository.findById(req.userId())
                        .orElseThrow(() -> new NotFoundException("User not found"));
                resolved = List.of(u.getId());
            }
            case "role" -> {
                if (req.roleCode() == null || req.roleCode().isBlank()) {
                    throw new BadRequestException("roleCode is required when target=role");
                }
                resolved = userRepository.findUserIdsByActiveRoleCode(req.roleCode().toUpperCase());
            }
            case "all" -> resolved = userRepository.findAllByEnabledTrue().stream()
                    .map(User::getId)
                    .toList();
            default -> throw new BadRequestException(
                    "target must be one of: user, role, all");
        }

        // Drop dupes before delegating so the count is honest.
        Set<UUID> unique = new HashSet<>(resolved);
        int dupes = resolved.size() - unique.size();
        int delivered = notificationService.sendBatch(
                unique, KIND_ADMIN, req.title(), req.body(), req.link());

        auditLogService.record(actorId, "ADMIN", "notification.admin.send",
                "notification", null, null,
                Map.of(
                        "target", target,
                        "roleCode", req.roleCode() == null ? "" : req.roleCode().toUpperCase(),
                        "kind", req.kind(),
                        "title", req.title(),
                        "body", req.body() == null ? "" : req.body(),
                        "link", req.link() == null ? "" : req.link(),
                        "recipients", delivered));

        return new SendNotificationResponse(
                target,
                req.roleCode() == null ? null : req.roleCode().toUpperCase(),
                req.title(),
                req.kind(),
                delivered,
                dupes);
    }
}