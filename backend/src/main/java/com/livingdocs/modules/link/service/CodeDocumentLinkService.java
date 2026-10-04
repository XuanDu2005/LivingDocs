package com.livingdocs.modules.link.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.link.dto.CodeDocumentLinkResponse;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages doc ↔ code entity links. Used to navigate between documentation
 * and source code, and to power the knowledge base.
 */
@Service
public class CodeDocumentLinkService {

    private final CodeDocumentLinkRepository linkRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceService workspaceService;

    public CodeDocumentLinkService(CodeDocumentLinkRepository linkRepository,
                                   DocumentRepository documentRepository,
                                   WorkspaceService workspaceService) {
        this.linkRepository = linkRepository;
        this.documentRepository = documentRepository;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public CodeDocumentLinkResponse link(UUID actorId, UUID documentId, UUID codeEntityId,
                                         String linkKind, Float confidence) {
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        CodeDocumentLink link = linkRepository.findByDocumentIdAndCodeEntityId(documentId, codeEntityId)
                .orElseGet(() -> new CodeDocumentLink(documentId, codeEntityId,
                        linkKind == null ? "REFERENCE" : linkKind, confidence));
        return CodeDocumentLinkResponse.from(linkRepository.save(link));
    }

    @Transactional(readOnly = true)
    public List<CodeDocumentLinkResponse> linksForDocument(UUID actorId, UUID documentId) {
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        return linkRepository.findAllByDocumentId(documentId).stream()
                .map(CodeDocumentLinkResponse::from).toList();
    }

    @Transactional
    public void unlinkDocument(UUID actorId, UUID documentId) {
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        linkRepository.deleteByDocumentId(documentId);
    }
}