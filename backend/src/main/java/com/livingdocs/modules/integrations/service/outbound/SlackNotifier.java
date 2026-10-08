package com.livingdocs.modules.integrations.service.outbound;

import com.livingdocs.modules.integrations.event.IntegrationOutboundEvent;
import com.livingdocs.modules.integrations.model.IntegrationConnection;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.model.IntegrationStatus;
import com.livingdocs.modules.integrations.repository.IntegrationConnectionRepository;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Posts Slack messages for {@link IntegrationOutboundEvent}s published
 * by other modules.
 *
 * <p>Targets are resolved per event:
 * <li>If the event carries a {@code workspaceId}, the notifier first tries
 *     to find a Slack connection scoped to that workspace. If none is
 *     active, it falls back to any platform-wide Slack connection.
 * <li>If the event carries no workspace (e.g. an admin broadcast), only
 *     platform-wide connections are considered.
 *
 * <p>Failures are logged at WARN — they never throw, never block the
 * caller. The caller has already finished its own business logic by the
 * time this listener runs.
 */
@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final IntegrationConnectionService connectionService;
    private final IntegrationConnectionRepository repository;
    private final RestClient restClient;

    public SlackNotifier(IntegrationConnectionService connectionService,
                         IntegrationConnectionRepository repository) {
        this.connectionService = connectionService;
        this.repository = repository;
        this.restClient = RestClient.create();
    }

    @EventListener
    @Async
    public void onOutboundEvent(IntegrationOutboundEvent event) {
        if (event == null || event.provider() != IntegrationProvider.SLACK) {
            return;
        }
        List<IntegrationConnection> targets = resolveTargets(event);
        for (IntegrationConnection c : targets) {
            try {
                send(c, event);
                connectionService.touchLastUsed(c.getId());
            } catch (Exception ex) {
                log.warn("Slack notification to {} failed: {}",
                        c.getExternalAccount(), ex.getMessage());
            }
        }
    }

    private List<IntegrationConnection> resolveTargets(IntegrationOutboundEvent event) {
        List<IntegrationConnection> rows = repository
                .findAllByProviderOrderByConnectedAtDesc(IntegrationProvider.SLACK);
        return rows.stream()
                .filter(c -> c.getStatus() == IntegrationStatus.ACTIVE)
                .filter(c -> matchesScope(c, event))
                .toList();
    }

    private static boolean matchesScope(IntegrationConnection c, IntegrationOutboundEvent event) {
        if (event.workspaceId() == null) {
            // No workspace — only deliver to platform-wide connections.
            return c.getWorkspaceId() == null;
        }
        if (Objects.equals(c.getWorkspaceId(), event.workspaceId())) {
            return true;
        }
        return c.getWorkspaceId() == null;
    }

    private void send(IntegrationConnection c, IntegrationOutboundEvent event) {
        String token = connectionService.requireAccessToken(c.getId());
        String channel = c.getDefaultChannel();
        if (channel == null || channel.isBlank()) {
            log.debug("Slack connection {} has no default_channel; dropping event {}", c.getId(), event.kind());
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("channel", channel);
        body.put("text", buildText(event));

        Map<String, Object> blocks = new HashMap<>();
        blocks.put("type", "section");
        Map<String, Object> text = new HashMap<>();
        text.put("type", "mrkdwn");
        text.put("text", "*" + escapeMd(event.title()) + "*"
                + (event.body() == null || event.body().isBlank() ? "" : "\n" + escapeMd(event.body())));
        blocks.put("text", text);
        if (event.link() != null && !event.link().isBlank()) {
            Map<String, Object> linkText = new HashMap<>();
            linkText.put("type", "mrkdwn");
            linkText.put("text", "<" + event.link() + "|View in LivingDocs>");
            Map<String, Object> linkBlock = new HashMap<>();
            linkBlock.put("type", "section");
            linkBlock.put("text", linkText);
            body.put("blocks", java.util.List.of(blocks, linkBlock));
        } else {
            body.put("blocks", java.util.List.of(blocks));
        }

        Map<?, ?> response = restClient.post()
                .uri("https://slack.com/api/chat.postMessage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response != null && Boolean.FALSE.equals(response.get("ok"))) {
            log.warn("Slack API rejected the message: {}", response.get("error"));
        }
    }

    private static String buildText(IntegrationOutboundEvent event) {
        StringBuilder sb = new StringBuilder(event.title());
        if (event.body() != null && !event.body().isBlank()) {
            sb.append(" — ").append(event.body());
        }
        if (event.link() != null && !event.link().isBlank()) {
            sb.append(" (").append(event.link()).append(")");
        }
        return sb.toString();
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        // Slack mrkdwn treats <, >, & specially inside text blocks; the
        // simplest safe transform is to escape the three characters that
        // would otherwise be interpreted as formatting.
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}