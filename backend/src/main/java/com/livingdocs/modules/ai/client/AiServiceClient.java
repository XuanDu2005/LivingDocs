package com.livingdocs.modules.ai.client;

import com.livingdocs.modules.ai.config.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the AI service REST API.
 *
 * <p>Every call is wrapped in a try/catch and degrades gracefully — when
 * the AI service is down, the backend keeps working with the manual
 * workflow. The caller checks for {@code null} and proceeds accordingly.
 */
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient restClient;
    private final AiServiceProperties props;

    public AiServiceClient(RestClient restClient, AiServiceProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public AiDtos.GenerateResponse generate(List<AiDtos.SourceFile> files,
                                          String template,
                                          String focus,
                                          String languageHint) {
        if (!props.isEnabled()) return null;
        try {
            AiDtos.GenerateRequest req = new AiDtos.GenerateRequest(files, template, focus, languageHint);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/generate")
                    .body(req)
                    .retrieve()
                    .body(AiDtos.GenerateResponse.class);
        } catch (Exception e) {
            log.warn("AI service generate call failed: {}", e.getMessage());
            return null;
        }
    }

    public AiDtos.DriftResponse detectDrift(List<AiDtos.SourceFile> filesBefore,
                                            List<AiDtos.SourceFile> filesAfter,
                                            String documentMarkdown,
                                            String documentTitle) {
        if (!props.isEnabled()) return null;
        try {
            AiDtos.DriftRequest req = new AiDtos.DriftRequest(
                    filesBefore, filesAfter, documentMarkdown, documentTitle);
            return restClient.post()
                    .uri(props.getApiPrefix() + "/drift")
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
}