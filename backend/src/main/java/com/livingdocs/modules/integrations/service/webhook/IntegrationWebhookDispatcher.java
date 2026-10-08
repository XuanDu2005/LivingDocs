package com.livingdocs.modules.integrations.service.webhook;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.modules.integrations.model.IntegrationConnection;
import com.livingdocs.modules.integrations.model.IntegrationEvent;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.repository.IntegrationConnectionRepository;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central inbound-webhook dispatcher.
 *
 * <p>For each inbound delivery:
 * <ol>
 *   <li>Enumerate the provider's ACTIVE connections.</li>
 *   <li>Try the configured signing secret on each one. The first that
 *       matches is treated as the destination connection; if none match
 *       the request is rejected with 401 (signature mismatch).</li>
 *   <li>Delegate to the provider-specific handler which extracts the
 *       event and decides whether to fan-out to in-app notifications.</li>
 *   <li>Map the result's {@code OutboundNotification} onto the members
 *       of the destination workspace and call
 *       {@link NotificationService#sendBatch}.</li>
 * </ol>
 *
 * <p>The dispatcher is the only place that knows about cross-handler
 * concerns (signature multiplexing, fan-out to workspace members); the
 * individual handler implementations stay provider-specific.
 */
@Service
public class IntegrationWebhookDispatcher {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationConnectionService connectionService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final NotificationService notificationService;
    private final Map<IntegrationProvider, IntegrationWebhookHandler> handlers;

    public IntegrationWebhookDispatcher(IntegrationConnectionRepository connectionRepository,
                                        IntegrationConnectionService connectionService,
                                        WorkspaceMemberRepository workspaceMemberRepository,
                                        NotificationService notificationService,
                                        List<IntegrationWebhookHandler> handlerList) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.notificationService = notificationService;
        this.handlers = new java.util.EnumMap<>(IntegrationProvider.class);
        for (IntegrationWebhookHandler h : handlerList) {
            this.handlers.put(h.provider(), h);
        }
    }

    @Transactional
    public DispatchResult dispatch(IntegrationProvider provider,
                                    String rawBody,
                                    Map<String, String> headers) {
        IntegrationWebhookHandler handler = handlers.get(provider);
        if (handler == null) {
            throw new NotFoundException("Unsupported provider: " + provider);
        }

        List<IntegrationConnection> candidates = connectionRepository
                .findAllByProviderOrderByConnectedAtDesc(provider);
        if (candidates.isEmpty()) {
            throw new UnauthorizedException(
                    "No integration connection found for provider " + provider);
        }

        IntegrationConnection matched = null;
        for (IntegrationConnection c : candidates) {
            String secret = connectionService.requireWebhookSecret(c.getId());
            if (secret.isBlank()) continue;
            try {
                handler.verifySignature(rawBody, headers, secret);
                matched = c;
                break;
            } catch (UnauthorizedException ignored) {
                // Try the next candidate.
            }
        }
        if (matched == null) {
            throw new UnauthorizedException(
                    "Webhook signature did not match any configured integration");
        }

        IntegrationWebhookHandler.WebhookResult result = handler.handle(rawBody, headers);
        // Now that signature verification has identified the connection,
        // attach it to the event row so admins can drill from a delivery
        // back to the connection (and the connection row can list its
        // own recent events).
        if (result.event() != null && result.event().getConnectionId() == null) {
            connectionService.attachConnection(result.event().getId(), matched.getId());
        }
        int delivered = 0;
        for (IntegrationWebhookHandler.OutboundNotification n : result.notifications()) {
            List<UUID> recipients = n.userIds();
            if (recipients == null || recipients.isEmpty()) {
                recipients = resolveRecipients(matched);
            }
            if (recipients.isEmpty()) {
                continue;
            }
            notificationService.sendBatch(recipients, n.kind(), n.title(), n.body(), n.link());
            delivered += recipients.size();
        }

        connectionService.touchLastUsed(matched.getId());
        return new DispatchResult(matched, result.event(), delivered);
    }

    private List<UUID> resolveRecipients(IntegrationConnection c) {
        UUID workspaceId = c.getWorkspaceId();
        if (workspaceId == null) {
            // Platform-wide connection — broadcast to all enabled users.
            // We delegate to the user repository via the notification
            // service: ask for a "broadcast to all" by sending to a
            // single sentinel kind and letting sendBatch dedupe.
            // Concrete impl: send to an empty list which the controller
            // can render as "no recipients" (admin can connect a
            // workspace-scoped channel instead). For phase 1 MVP we
            // simply return empty here; admins are expected to link
            // per-workspace channels for fan-out.
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (var m : workspaceMemberRepository.findAllByWorkspaceId(workspaceId)) {
            ids.add(m.getUserId());
        }
        return ids;
    }

    /**
     * Holder returned to the controller so it can build the HTTP
     * response (challenge echo for Slack, OK status for the rest).
     */
    public record DispatchResult(
            IntegrationConnection connection,
            IntegrationEvent event,
            int delivered
    ) {
    }
}