package com.livingdocs.modules.document.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.dto.CreateDocumentRequest;
import com.livingdocs.modules.document.dto.DocumentResponse;
import com.livingdocs.modules.document.dto.UpdateDocumentRequest;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.template.model.DocType;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CRUD + workflow operations for {@link Document}s.
 *
 * <p>Authorization rules:
 * <ul>
 *     <li>Reading a document — any workspace member.</li>
 *     <li>Creating a document — any workspace member; the creator becomes the
 *         {@code ownerId}.</li>
 *     <li>Updating metadata — owner or workspace manager.</li>
 *     <li>Deleting a document — workspace manager only.</li>
 * </ul>
 *
 * <p>Body changes are routed through
 * {@link com.livingdocs.modules.version.service.DocumentVersionService}, which
 * preserves an immutable change log.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final WorkspaceService workspaceService;
    private final DocTemplateService templateService;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentVersionRepository versionRepository,
                           WorkspaceService workspaceService,
                           DocTemplateService templateService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.workspaceService = workspaceService;
        this.templateService = templateService;
    }

    @Transactional
    public DocumentResponse create(UUID actorId, UUID workspaceId, CreateDocumentRequest req) {
        workspaceService.requireMember(actorId, workspaceId);
        String normalizedSlug = req.slug().toLowerCase(Locale.ROOT);
        if (documentRepository.findByWorkspaceIdAndSlug(workspaceId, normalizedSlug).isPresent()) {
            throw new ConflictException("A document with this slug already exists in this workspace");
        }
        Document document = new Document(
                workspaceId,
                req.repositoryId(),
                req.templateId(),
                req.title(),
                normalizedSlug,
                req.docType(),
                req.summary(),
                req.autoUpdateEnabled(),
                actorId
        );
        Document saved = documentRepository.save(document);

        // Initial version — a draft attributed to the author.
        DocumentVersion initial = new DocumentVersion(
                saved.getId(), 1, req.initialBody(), "Initial version",
                ActorRole.STAFF, actorId,
                null, null, saved.getTemplateId(),
                VersionStatus.PENDING, null
        );
        versionRepository.save(initial);

        log.info("Document created: workspace={} slug={} docType={}",
                workspaceId, normalizedSlug, req.docType());
        return DocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID actorId, UUID workspaceId, UUID documentId) {
        Document d = loadAndAuthorize(actorId, workspaceId, documentId);
        return DocumentResponse.from(d);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID actorId, UUID workspaceId, String docType) {
        workspaceService.requireMember(actorId, workspaceId);
        List<Document> docs = (docType == null || docType.isBlank())
                ? documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId)
                : documentRepository.findAllByWorkspaceIdAndDocTypeOrderByUpdatedAtDesc(workspaceId, docType);
        return docs.stream().map(DocumentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listByRepository(UUID actorId, UUID workspaceId, UUID repositoryId) {
        workspaceService.requireMember(actorId, workspaceId);
        return documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId)
                .stream().map(DocumentResponse::from).toList();
    }

    @Transactional
    public DocumentResponse update(UUID actorId, UUID workspaceId, UUID documentId,
                                   UpdateDocumentRequest req) {
        Document d = loadAndAuthorize(actorId, workspaceId, documentId);
        if (!d.getOwnerId().equals(actorId)) {
            workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        }
        d.setTitle(req.title());
        d.setSummary(req.summary());
        if (req.repositoryId() != null) {
            d.setRepositoryId(req.repositoryId());
        }
        if (req.templateId() != null) {
            d.setTemplateId(req.templateId());
        }
        d.setAutoUpdateEnabled(req.autoUpdateEnabled());
        return DocumentResponse.from(d);
    }

    @Transactional
    public void delete(UUID actorId, UUID workspaceId, UUID documentId) {
        Document d = loadAndAuthorize(actorId, workspaceId, documentId);
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        documentRepository.delete(d);
    }

    /**
     * Look up a document by its workspace-scoped slug.
     */
    @Transactional(readOnly = true)
    public Document findBySlug(UUID actorId, UUID workspaceId, String slug) {
        workspaceService.requireMember(actorId, workspaceId);
        return documentRepository.findByWorkspaceIdAndSlug(workspaceId, slug.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("Document not found"));
    }

    /**
     * Resolve the template to use for a given document type, preferring
     * workspace overrides over the global library.
     */
    public String defaultTemplateFor(UUID workspaceId, String docType) {
        var t = templateService.resolveDefault(workspaceId, docType);
        return t == null ? null : t.getId().toString();
    }

    /**
     * Sanity-check that {@code docType} is one of the canonical enum values.
     */
    public static String normalizeDocType(String raw) {
        return DocType.fromString(raw).name();
    }

    private Document loadAndAuthorize(UUID actorId, UUID workspaceId, UUID documentId) {
        if (workspaceId == null) {
            throw new BadRequestException("workspaceId is required");
        }
        Document d = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (!d.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Document not found");
        }
        workspaceService.requireMember(actorId, workspaceId);
        return d;
    }

    /**
     * Toggle the auto-update setting for a document. If enabled, the system will
     * automatically regenerate the document when the linked code repository changes.
     */
    @Transactional
    public Document toggleAutoUpdate(UUID actorId, UUID workspaceId, UUID documentId, boolean enabled) {
        workspaceService.requireMember(actorId, workspaceId);
        
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        
        if (!doc.getWorkspaceId().equals(workspaceId)) {
            throw new ForbiddenException("Document does not belong to this workspace");
        }
        
        boolean previous = doc.isAutoUpdateEnabled();
        if (previous != enabled) {
            doc.setAutoUpdateEnabled(enabled);
            documentRepository.save(doc);
            
            auditLogService.record(actorId, "MEMBER", "document.auto_update.toggle",
                    "document", documentId.toString(), workspaceId,
                    Map.of("enabled", enabled, "previous", previous, "title", doc.getTitle()));
        }
        return doc;
    }
}