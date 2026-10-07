package com.livingdocs.modules.ai.client;

import com.livingdocs.modules.ai.config.AiServiceProperties;
import com.livingdocs.modules.ai.settings.AiProvider;
import com.livingdocs.modules.ai.settings.AiSettings;
import com.livingdocs.modules.ai.settings.AiSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper around the AI service REST API.
 *
 * <p>Two backends are supported:
 * <ul>
 *   <li><b>Local AI service</b> ({@code http://localhost:8000}) — used
 *       for sandboxed runs and the legacy drift/index/search code paths.
 *       Controlled by {@code ai-service.enabled}.</li>
 *   <li><b>Workspace provider</b> — picked up via
 *       {@link AiSettingsService} per request. The actual call to
 *       OpenAI / Anthropic / Ollama is made by the AI service itself,
 *       which reads the {@code apiKey}, {@code model}, and {@code baseUrl}
 *       from the workspace settings and forwards to the right vendor.</li>
 * </ul>
 *
 * <p>All calls fail soft: when the AI service is down or settings are
 * missing, the caller sees a {@code null} return value and continues
 * with the manual workflow.
 */
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient restClient;
    private final AiServiceProperties props;
    private final AiSettingsService settingsService;

    public AiServiceClient(RestClient restClient,
                           AiServiceProperties props,
                           AiSettingsService settingsService) {
        this.restClient = restClient;
        this.props = props;
        this.settingsService = settingsService;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * Resolve the effective AI settings for a workspace, falling back to
     * sandbox defaults if the workspace has not configured anything yet.
     *
     * <p>This bypasses the membership check so it can be used from
     * background workers that may act on behalf of any user. The
     * security boundary is enforced upstream in the controller layer.
     */
    public AiSettings resolveSettings(UUID workspaceId) {
        if (workspaceId == null) return AiSettings.defaults();
        try {
            return settingsService.resolveForAiClient(workspaceId);
        } catch (Exception e) {
            return AiSettings.defaults();
        }
    }

    public AiDtos.GenerateResponse generate(UUID workspaceId,
                                            List<AiDtos.SourceFile> files,
                                            String template,
                                            String focus,
                                            String languageHint) {
        if (!props.isEnabled()) return null;
        AiSettings settings = resolveSettings(workspaceId);
        try {
            AiDtos.GenerateRequest req = new AiDtos.GenerateRequest(files, template, focus, languageHint);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/generate")
                    .header("X-Workspace-Id", workspaceId == null ? "" : workspaceId.toString())
                    .header("X-Ai-Provider", settings.provider().name())
                    .header("X-Ai-Model", settings.model() == null ? "" : settings.model())
                    .header("X-Ai-Base-Url", settings.baseUrl() == null ? "" : settings.baseUrl())
                    .body(req)
                    .retrieve()
                    .body(AiDtos.GenerateResponse.class);
        } catch (Exception e) {
            log.warn("AI service generate call failed: {}", e.getMessage());
            return null;
        }
    }

    public AiDtos.DriftResponse detectDrift(UUID workspaceId,
                                            List<AiDtos.SourceFile> filesBefore,
                                            List<AiDtos.SourceFile> filesAfter,
                                            String documentMarkdown,
                                            String documentTitle) {
        if (!props.isEnabled()) return null;
        AiSettings settings = resolveSettings(workspaceId);
        try {
            AiDtos.DriftRequest req = new AiDtos.DriftRequest(
                    filesBefore, filesAfter, documentMarkdown, documentTitle);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/drift")
                    .header("X-Workspace-Id", workspaceId == null ? "" : workspaceId.toString())
                    .header("X-Ai-Provider", settings.provider().name())
                    .header("X-Ai-Model", settings.model() == null ? "" : settings.model())
                    .body(req)
                    .retrieve()
                    .body(AiDtos.DriftResponse.class);
        } catch (Exception e) {
            log.warn("AI service drift call failed: {}", e.getMessage());
            return null;
        }
    }

    public AiDtos.IndexResponse indexDocument(String documentId, String title, String body,
                                              String docType, String workspaceId,
                                              Map<String, Object> metadata) {
        if (!props.isEnabled()) return null;
        try {
            AiDtos.IndexRequest req = new AiDtos.IndexRequest(
                    documentId, title, body, docType, workspaceId, metadata);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/knowledge/index")
                    .body(req)
                    .retrieve()
                    .body(AiDtos.IndexResponse.class);
        } catch (Exception e) {
            log.warn("AI service index call failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean removeIndex(String documentId) {
        if (!props.isEnabled()) return false;
        try {
            restClient.delete()
                    .uri(UriComponentsBuilder.fromPath(props.getApiPrefix() + "/knowledge/index/{id}")
                            .buildAndExpand(documentId).toUri())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("AI service delete-index call failed: {}", e.getMessage());
            return false;
        }
    }

    public AiDtos.SearchResponse search(String query, String workspaceId, int topK) {
        if (!props.isEnabled()) return null;
        try {
            AiDtos.SearchRequest req = new AiDtos.SearchRequest(query, workspaceId, topK);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/knowledge/search")
                    .body(req)
                    .retrieve()
                    .body(AiDtos.SearchResponse.class);
        } catch (Exception e) {
            log.warn("AI service search call failed: {}", e.getMessage());
            return null;
        }
    }

    public AiProvider defaultProvider() {
        return AiProvider.SANDBOX;
    }
}
