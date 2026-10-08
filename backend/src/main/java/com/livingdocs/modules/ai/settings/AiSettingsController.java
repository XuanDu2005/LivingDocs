package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Workspace-scoped AI configuration endpoints. The plaintext API key is
 * only accepted on PUT and is never echoed back on GET — the response
 * only indicates that a key is configured plus a masked preview.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/ai-settings")
@Tag(name = "AI Settings", description = "Per-workspace AI provider/model/key configuration")
public class AiSettingsController {

    private final AiSettingsService service;
    private final ConfidenceThresholdService thresholdService;

    public AiSettingsController(AiSettingsService service, ConfidenceThresholdService thresholdService) {
        this.service = service;
        this.thresholdService = thresholdService;
    }

    @GetMapping
    @Operation(summary = "Get the workspace's AI settings (API key is masked)")
    public AiSettings get(@PathVariable UUID workspaceId) {
        return service.get(CurrentUser.requireId(), workspaceId);
    }

    @PutMapping
    @Operation(summary = "Update AI settings; pass apiKey to set, clearApiKey=true to remove")
    public AiSettings update(@PathVariable UUID workspaceId,
                             @Valid @RequestBody UpdateAiSettingsRequest req) {
        return service.update(CurrentUser.requireId(), workspaceId, req);
    }

    @GetMapping("/test")
    @Operation(summary = "Verify the workspace's AI configuration responds")
    public Map<String, Object> test(@PathVariable UUID workspaceId) {
        CurrentUser.requireId();
        // For now the "test" endpoint just returns the resolved config
        // summary. A future iteration can fan out to the provider's
        // /v1/models endpoint to confirm the key is valid.
        AiSettings s = service.get(CurrentUser.requireId(), workspaceId);
        return Map.of(
                "status", "ok",
                "provider", s.provider().name(),
                "model", s.model(),
                "hasApiKey", s.hasApiKey());
    }

    @GetMapping("/threshold")
    @Operation(summary = "Get the workspace's AI confidence threshold (0.0 - 1.0)")
    public Map<String, Object> getThreshold(@PathVariable UUID workspaceId) {
        return Map.of("value", thresholdService.get(workspaceId));
    }

    @PutMapping("/threshold")
    @Operation(summary = "Update the AI confidence threshold (MANAGER+ only)")
    public Map<String, Object> updateThreshold(@PathVariable UUID workspaceId,
                                                @Valid @RequestBody ConfidenceThresholdRequest req) {
        float value = thresholdService.update(CurrentUser.requireId(), workspaceId, req.value());
        return Map.of("value", value);
    }
}
