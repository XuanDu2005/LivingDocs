package com.livingdocs.modules.integrations.event;

import com.livingdocs.modules.integrations.model.IntegrationProvider;

import java.util.UUID;

/**
 * Application-wide event published when something interesting happens
 * that may be mirrored to one or more external integrations (Slack in
 * the current implementation, more providers later).
 *
 * <p>The {@code kind} field is a short stable identifier (e.g.
 * {@code "DRIFT_DETECTED"}, {@code "PR_MERGED"}, {@code "ADMIN_BROADCAST"})
 * that outbound adapters use to format messages consistently.
 */
public record IntegrationOutboundEvent(
        UUID workspaceId,
        IntegrationProvider provider,
        String kind,
        String title,
        String body,
        String link
) {
}