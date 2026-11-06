package com.livingdocs.modules.document.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final AuditLogService auditLogService;
    private final AuthorizationService authorizationService;
    private final PermissionService permissionService;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentVersionRepository versionRepository,
                           WorkspaceService workspaceService,
                           DocTemplateService templateService,
                           AuditLogService auditLogService,
                           AuthorizationService authorizationService,
                           PermissionService permissionService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.workspaceService = workspaceService;
        this.templateService = templateService;
        this.auditLogService = auditLogService;
        this.authorizationService = authorizationService;
        this.permissionService = permissionService;
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

        // Initial version — a draft attributed to the author. It stays a
        // draft until the author submits it for review.
        DocumentVersion initial = new DocumentVersion(
                saved.getId(), 1, req.initialBody(), "Initial version",
                actorRoleFor(actorId), actorId,
                null, null, saved.getTemplateId(),
                VersionStatus.DRAFT, null
        );
        DocumentVersion savedInitial = versionRepository.save(initial);

        // The creation of the document is the first entry of its trail.
        // Without it the trail would start at the first edit, and a
        // reader could not tell whether the document was created by a
        // person or appeared from nowhere.
        String initialVersionId = savedInitial.getId() != null
                ? savedInitial.getId().toString() : "";
        Integer initialVersionNumber = savedInitial.getVersionNumber() != null
                ? savedInitial.getVersionNumber() : 0;
        auditLogService.record(actorId, actorRoleFor(actorId).name(),
                "document.created", "document", saved.getId().toString(),
                workspaceId, saved.getId(),
                Map.of("documentId", saved.getId().toString(),
                        "slug", normalizedSlug,
                        "title", req.title(),
                        "docType", req.docType(),
                        "versionId", initialVersionId,
                        "versionNumber", initialVersionNumber));

        log.info("Document created: workspace={} slug={} docType={}",
                workspaceId, normalizedSlug, req.docType());
        return DocumentResponse.from(saved);
    }

    /**
     * The role to attribute a human edit to.
     *
     * <p>Derived from the caller's platform role. Before this existed the
     * initial version of every document was labelled STAFF, so a
     * DEVELOPER-authored document looked like a Staff document in the
     * change log.
     */
    private ActorRole actorRoleFor(UUID actorId) {
        var roles = authorizationService.platformRoles(actorId);
        if (roles.contains(AuthorizationService.ROLE_ADMIN)
                || roles.contains(AuthorizationService.ROLE_MANAGER)
                || roles.contains(AuthorizationService.ROLE_TECHNICAL_LEAD)) {
            return ActorRole.MANAGER;
        }
        if (roles.contains(AuthorizationService.ROLE_DEVELOPER)) {
            return ActorRole.DEVELOPER;
        }
        return ActorRole.STAFF;
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

    /**
     * Update the metadata of a document.
     *
     * <p>Two guards apply, and both are needed:
     * <ul>
     *   <li>the {@code DOCUMENT_UPDATE} permission — the matrix decides
     *       whether the role may edit documents at all;</li>
     *   <li>the ownership rule — the owner of the document, or a
     *       workspace manager. That is a business rule the permission
     *       matrix cannot express, so it stays here.</li>
 * </ul>
 *
 * <p>Before this, the ownership rule was the only check, which meant
 * the {@code DOCUMENT_UPDATE} permission existed in the matrix but was
 * never consulted — the matrix was decorative for this endpoint.
 */
    @Transactional
    public DocumentResponse update(UUID actorId, UUID workspaceId, UUID documentId,
                                   UpdateDocumentRequest req) {
        Document d = loadAndAuthorize(actorId, workspaceId, documentId);
        permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_UPDATE);
        if (!d.getOwnerId().equals(actorId)) {
            workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        }
        // The previous values are captured before the mutation, so the
        // audit entry can say what actually changed instead of restating
        // the whole document.
        String previousTitle = d.getTitle();
        String previousSummary = d.getSummary();
        UUID previousRepositoryId = d.getRepositoryId();
        UUID previousTemplateId = d.getTemplateId();
        boolean previousAutoUpdate = d.isAutoUpdateEnabled();

        d.setTitle(req.title());
        d.setSummary(req.summary());
        if (req.repositoryId() != null) {
            d.setRepositoryId(req.repositoryId());
        }
        if (req.templateId() != null) {
            d.setTemplateId(req.templateId());
        }
        d.setAutoUpdateEnabled(req.autoUpdateEnabled());

        // The metadata of a document is part of what the Manager reviews,
        // so a change to it belongs in the trail. Only the fields that
        // actually changed are named, so the entry says what happened
        // instead of restating the whole document.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("documentId", documentId.toString());
        if (!Objects.equals(previousTitle, req.title())) {
            changes.put("titleChanged", true);
        }
        if (!Objects.equals(previousSummary, req.summary())) {
            changes.put("summaryChanged", true);
        }
        if (req.repositoryId() != null && !req.repositoryId().equals(previousRepositoryId)) {
            changes.put("repositoryId", req.repositoryId().toString());
        }
        if (req.templateId() != null && !req.templateId().equals(previousTemplateId)) {
            changes.put("templateId", req.templateId().toString());
        }
        if (previousAutoUpdate != req.autoUpdateEnabled()) {
            changes.put("autoUpdateEnabled", req.autoUpdateEnabled());
        }
        auditLogService.record(actorId, actorRoleFor(actorId).name(),
                "document.updated", "document", documentId.toString(),
                workspaceId, documentId, changes);

        return DocumentResponse.from(d);
    }

    @Transactional
    public void delete(UUID actorId, UUID workspaceId, UUID documentId) {
        Document d = loadAndAuthorize(actorId, workspaceId, documentId);
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        // The deletion is recorded before the row disappears. The audit
        // log is append-only and carries no foreign key to the document,
        // so the entry survives the deletion and the trail of the
        // document stays readable afterwards.
        auditLogService.record(actorId, actorRoleFor(actorId).name(),
                "document.deleted", "document", documentId.toString(),
                workspaceId, documentId,
                Map.of("documentId", documentId.toString(),
                        "slug", d.getSlug() == null ? "" : d.getSlug(),
                        "title", d.getTitle() == null ? "" : d.getTitle()));
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
     *
     * <p>Guarded by {@code REPOSITORY_AUTO_UPDATE_MANAGE} — the same
     * capability that controls the repository-level toggle. A per-document
     * permission would be more precise, but the document toggle is only
     * meaningful when the repository toggle is on, so splitting the two
     * would let somebody enable a document whose repository cannot
     * auto-update at all.
     */
    @Transactional
    public Document toggleAutoUpdate(UUID actorId, UUID workspaceId, UUID documentId, boolean enabled) {
        workspaceService.requireMember(actorId, workspaceId);
        permissionService.requireAnyPermission(actorId, PermissionCodes.REPOSITORY_AUTO_UPDATE_MANAGE);

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
                    "document", documentId.toString(), workspaceId, documentId,
                    Map.of("documentId", documentId.toString(),
                            "enabled", enabled, "previous", previous,
                            "title", doc.getTitle() == null ? "" : doc.getTitle()));
        }
        return doc;
    }
}