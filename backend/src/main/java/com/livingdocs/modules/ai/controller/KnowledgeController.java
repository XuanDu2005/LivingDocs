package com.livingdocs.modules.ai.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Knowledge-base operations: index a document so it can be retrieved via
 * the AI service's vector store, search the knowledge base, and remove a
 * document from the index.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Knowledge", description = "Knowledge-base indexing + search")
public class KnowledgeController {

    private final AiServiceClient aiClient;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final WorkspaceService workspaceService;

    public KnowledgeController(AiServiceClient aiClient,
                               DocumentRepository documentRepository,
                               DocumentVersionService versionService,
                               WorkspaceService workspaceService) {
        this.aiClient = aiClient;
        this.documentRepository = documentRepository;
        this.versionService = versionService;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/knowledge/index")
    @Operation(summary = "Index a document's body in the AI knowledge base")
    public ResponseEntity<Map<String, Object>> index(@PathVariable UUID workspaceId,
                                                      @PathVariable UUID documentId) {
        UUID actorId = CurrentUser.requireId();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, workspaceId);

        String body = versionService.findLatestBody(documentId).orElse("");
        AiDtos.IndexResponse resp = aiClient.indexDocument(
                documentId.toString(),
                document.getTitle(),
                body,
                document.getDocType(),
                workspaceId.toString(),
                Map.of("slug", document.getSlug(), "owner", document.getOwnerId().toString()));
        if (resp == null) {
            return ResponseEntity.ok(Map.of("chunks_indexed", 0, "status", "ai_service_unavailable"));
        }
        return ResponseEntity.ok(Map.of(
                "chunks_indexed", resp.chunksIndexed(),
                "status", "indexed"));
    }

    @PostMapping("/workspaces/{workspaceId}/knowledge/search")
    @Operation(summary = "Semantic search over the workspace's knowledge base")
    public ResponseEntity<Map<String, Object>> search(@PathVariable UUID workspaceId,
                                                       @RequestBody SearchRequest req) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        AiDtos.SearchResponse resp = aiClient.search(
                req.query(), workspaceId.toString(), req.topK() == null ? 5 : req.topK());
        if (resp == null) {
            return ResponseEntity.ok(Map.of("hits", java.util.List.of(), "status", "ai_service_unavailable"));
        }
        return ResponseEntity.ok(Map.of("hits", resp.hits()));
    }

    @DeleteMapping("/workspaces/{workspaceId}/documents/{documentId}/knowledge/index")
    @Operation(summary = "Remove a document from the AI knowledge base index")
    public Map<String, Object> remove(@PathVariable UUID workspaceId,
                                        @PathVariable UUID documentId) {
        UUID actorId = CurrentUser.requireId();
        workspaceService.requireMember(actorId, workspaceId);
        boolean ok = aiClient.removeIndex(documentId.toString());
        return Map.of("status", ok ? "removed" : "ai_service_unavailable");
    }

    public record SearchRequest(String query, Integer topK) {}
}