package com.livingdocs.modules.notification.service;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.notification.dto.NotificationChannelResponse;
import com.livingdocs.modules.notification.dto.UpdateNotificationChannelRequest;
import com.livingdocs.modules.notification.model.NotificationChannel;
import com.livingdocs.modules.notification.repository.NotificationChannelRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationChannelService {

    private final NotificationChannelRepository repository;
    private final WorkspaceService workspaceService;

    public NotificationChannelService(NotificationChannelRepository repository,
                                       WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public List<NotificationChannelResponse> listForWorkspace(UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MEMBER);
        return repository.findAllByWorkspaceIdOrderByEventKindAsc(workspaceId).stream()
                .map(NotificationChannelResponse::from)
                .toList();
    }

    @Transactional
    public NotificationChannelResponse updateChannel(UUID workspaceId,
                                                      UpdateNotificationChannelRequest req) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MANAGER);

        NotificationChannel channel = repository
                .findByWorkspaceIdAndEventKind(workspaceId, req.eventKind())
                .orElseGet(() -> NotificationChannel.of(
                        workspaceId, req.eventKind(), true, false, null, actor));

        channel.applyChange(req.inAppEnabled(), req.emailEnabled(), req.emailRecipients(), actor);
        return NotificationChannelResponse.from(repository.save(channel));
    }
}
