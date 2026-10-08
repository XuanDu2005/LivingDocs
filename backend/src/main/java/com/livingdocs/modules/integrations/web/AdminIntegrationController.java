package com.livingdocs.modules.integrations.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.integrations.dto.ConnectIntegrationRequest;
import com.livingdocs.modules.integrations.dto.ConnectionDto;
import com.livingdocs.modules.integrations.dto.EventDto;
import com.livingdocs.modules.integrations.dto.TestResponse;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administrator endpoints for managing integration connections
 * (GitHub, GitLab, Jira, Slack).
 *
 * <p>All endpoints require the {@code ADMIN} platform role. Workspace-
 * scoped connections are still visible here so admins can audit which
 * workspace holds which external account.
 */
@RestController
@RequestMapping("/api/v1/admin/integrations")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin · Integrations",
        description = "Manage connections to external services (GitHub, GitLab, Jira, Slack)")
public class AdminIntegrationController {

    private final IntegrationConnectionService service;

    public AdminIntegrationController(IntegrationConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List every integration connection")
    public List<ConnectionDto> list(@RequestParam(required = false) IntegrationProvider provider) {
        return provider == null ? service.listAll() : service.listByProvider(provider);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single connection")
    public ConnectionDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @Operation(summary = "Register a new connection")
    public ConnectionDto create(@Valid @RequestBody ConnectIntegrationRequest req) {
        return service.create(CurrentUser.requireId(), "ADMIN", req);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing connection (tokens only change if re-supplied)")
    public ConnectionDto update(@PathVariable UUID id, @Valid @RequestBody ConnectIntegrationRequest req) {
        return service.update(CurrentUser.requireId(), "ADMIN", id, req);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Probe the stored token against the provider's health endpoint")
    public TestResponse test(@PathVariable UUID id) {
        return service.test(CurrentUser.requireId(), "ADMIN", id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a connection (status -> REVOKED; tokens retained for audit)")
    public void revoke(@PathVariable UUID id) {
        service.revoke(CurrentUser.requireId(), "ADMIN", id);
    }

    @GetMapping("/events")
    @Operation(summary = "Recent inbound webhook deliveries (across all connections)")
    public List<EventDto> events(@RequestParam(defaultValue = "50") int limit) {
        return service.recentEvents(limit).stream().map(EventDto::of).toList();
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Recent inbound webhook deliveries for a single connection")
    public List<EventDto> eventsForConnection(@PathVariable UUID id,
                                               @RequestParam(defaultValue = "50") int limit) {
        return service.recentEventsForConnection(id, limit).stream().map(EventDto::of).toList();
    }
}