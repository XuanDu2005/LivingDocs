package com.livingdocs.modules.document.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The gate that decides whether a document may leave the platform.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The two-tier review workflow (Staff review → Manager approval →
 * publish) was supposed to be the guarantee that only reviewed content
 * reaches the repository. It was not: {@code commitDocument} only
 * checked that the caller held {@code SCM_COMMIT} and was a workspace
 * member. A user with that permission could push any text to any
 * repository — including content nobody had read — and the Manager
 * approval tier would have no say in it. The tier was decorative.
 *
 * <h2>The rule</h2>
 *
 * <p>A commit is allowed only when the version it publishes carries an
 * approval:
 *
 * <ul>
 *   <li>the version must exist and belong to the document;</li>
 *   <li>the version must be {@link VersionStatus#APPROVED} (a Manager
 *       granted final approval) or {@link VersionStatus#PUBLISHED} (it
 *       was approved and has since become the head);</li>
 *   <li>the committed content must contain the body of <em>that</em>
 *       version verbatim.</li>
 * </ul>
 *
 * <p>The last point is what closes the obvious loophole. Without it a
 * caller could point the request at an approved version (which passes
 * the first two checks) while sending completely different bytes — the
 * approved version would be a key to unlock a commit of unreviewed
 * content. The content check makes the approval bind to the bytes that
 * are actually published.
 *
 * <p>The content is allowed to <em>wrap</em> the approved body: the
 * frontend writes the file with a YAML frontmatter header followed by
 * the markdown body, and the frontmatter carries metadata (title, slug,
 * status) that is derived from the document rather than from the
 * reviewed text. So the rule is "the reviewed body is present, unchanged,
 * in the committed file" rather than "the committed file equals the
 * reviewed body".
 */
@Service
public class DocumentApprovalGateService {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentApprovalGateService.class);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;

    public DocumentApprovalGateService(DocumentRepository documentRepository,
                                       DocumentVersionRepository versionRepository) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
    }

    /**
     * Assert that the given version may be published to a repository.
     *
     * @param workspaceId   the workspace the commit happens in
     * @param documentId    the document being published
     * @param versionNumber the version being published
     * @param content       the content that would be written to the file
     * @throws NotFoundException when the document or the version does
     *         not exist, or the version belongs to another document
     * @throws ConflictException when the version carries no approval, or
     *         when the content does not contain the approved body
     */
    @Transactional(readOnly = true)
    public void requireApprovedForCommit(UUID workspaceId,
                                         UUID documentId,
                                         Integer versionNumber,
                                         String content) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (!document.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Document not found in this workspace");
        }

        DocumentVersion version = versionRepository
                .findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!version.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }

        if (version.getStatus() != VersionStatus.APPROVED
                && version.getStatus() != VersionStatus.PUBLISHED) {
            log.info("Commit refused for document {} version {}: status is {}",
                    documentId, versionNumber, version.getStatus());
            throw new ConflictException(
                    "Version " + versionNumber + " of this document has not been approved "
                            + "(current status: " + version.getStatus() + "). "
                            + "A document may only be committed to a repository after a Manager "
                            + "has given final approval.");
        }

        String approvedBody = version.getBodyMarkdown();
        if (approvedBody == null || approvedBody.isBlank()) {
            throw new ConflictException(
                    "Version " + versionNumber + " of this document has no content to publish.");
        }
        if (content == null || !content.contains(approvedBody)) {
            log.info("Commit refused for document {} version {}: the committed content does "
                    + "not match the approved body", documentId, versionNumber);
            throw new ConflictException(
                    "The content to commit does not match version " + versionNumber
                            + " of this document, which is the version that carries the approval. "
                            + "Commit the approved version, or send the new content through the "
                            + "review workflow first.");
        }
    }
}
