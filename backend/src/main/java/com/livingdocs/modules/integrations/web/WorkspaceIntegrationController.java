package com.livingdocs.modules.integrations.web;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.integrations.dto.ConnectIntegrationRequest;
import com.livingdocs.modules.integrations.dto.ConnectionDto;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.service.IntegrationConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-workspace integration listing. Workspace members see the
 * connections scoped to their workspace; platform-wide connections
 * (e.g. a global Slack workspace) are intentionally hidden from this
 * endpoint because they are configured by admins only.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/integrations")
@Tag(name = "Workspace · Integrations",
        description = "List integrations linked to a single workspace")
public class WorkspaceIntegrationController {

    private final IntegrationConnectionService service;

    public WorkspaceIntegrationController(IntegrationConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List integration connections for this workspace")
    public List<ConnectionDto> list(@PathVariable UUID workspaceId,
                                     @RequestParam(required = false) IntegrationProvider provider) {
        CurrentUser.requireId();
        var all = service.listByWorkspace(workspaceId);
        if (provider == null) return all;
        return all.stream().filter(c -> c.provider() == provider).toList();
    }

    @PostMapping
    @Operation(summary = "Link a new account to this workspace")
    public ConnectionDto connect(@PathVariable UUID workspaceId,
                                 @Valid @RequestBody ConnectIntegrationRequest req) {
        // Override whatever the client passed so the row is always tied
        // to the URL-scoped workspace.
        ConnectIntegrationRequest scoped = new ConnectIntegrationRequest(
                req.provider(),
                workspaceId,
                req.displayName(),
                req.externalAccount(),
                req.baseUrl(),
                req.scopes(),
                req.accessToken(),
                req.refreshToken(),
                req.webhookSecret(),
                req.defaultChannel()
        );
        return service.create(CurrentUser.requireId(), "MEMBER", scoped);
    }
}