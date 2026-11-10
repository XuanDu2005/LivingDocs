package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.review.dto.TemplateComplianceResponse;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateComplianceEvaluator;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Checks a document version against the template it was generated from.
 *
 * <p>Requirement #5 of the STAFF role: "verify template structure".
 *
 * <p>The template library already had a validator, but it only checked
 * the <em>shape of the template definition</em> — that every section
 * has an id and a heading — at the moment an administrator saved it.
 * Nothing ever compared the generated document against that template,
 * so a reviewer had to open the document and check by hand whether the
 * "API surface" section was there at all.
 *
 * <p>This service resolves the template the document uses and reports
 * which sections are present, which are missing, which are out of
 * order and which are empty. The comparison itself lives in
 * {@link TemplateComplianceEvaluator}, which is a pure function — the
 * version module uses it too, to decide whether a version may be
 * published, and that would otherwise be a circular dependency.
 */
@Service
public class TemplateComplianceService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocTemplateRepository templateRepository;
    private final DocTemplateService templateService;
    private final TemplateComplianceEvaluator evaluator;
    private final WorkspaceService workspaceService;

    public TemplateComplianceService(DocumentRepository documentRepository,
                                     DocumentVersionRepository versionRepository,
                                     DocTemplateRepository templateRepository,
                                     DocTemplateService templateService,
                                     TemplateComplianceEvaluator evaluator,
                                     WorkspaceService workspaceService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.evaluator = evaluator;
        this.workspaceService = workspaceService;
    }

    /**
     * Check one version of one document against its template.
     *
     * <p>Requires workspace membership — every role in the workspace may
     * look at a document's structure; the reviewer uses this to decide
     * whether the content is complete.
     */
    @Transactional(readOnly = true)
    public TemplateComplianceResponse check(UUID actorId, UUID documentId, UUID versionId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!version.getDocumentId().equals(documentId)) {
            throw new NotFoundException("Version not found");
        }

        DocTemplate template = resolveTemplate(document, version);
        if (template == null) {
            // Nothing to comply with. Reporting "not compliant" would be
            // misleading — there is simply no template for this document.
            return TemplateComplianceResponse.from(evaluator.evaluate(null, null));
        }

        return TemplateComplianceResponse.from(
                evaluator.evaluate(template, version.getBodyMarkdown()));
    }

    /**
     * Compare a body against a template's sections.
     *
     * <p>Split out from {@link #check} so the review queue can evaluate
     * the same template against many documents without repeating the
     * template parsing.
     */
    public TemplateComplianceResponse evaluate(DocTemplate template, String bodyMarkdown) {
        return TemplateComplianceResponse.from(evaluator.evaluate(template, bodyMarkdown));
    }

    /**
     * The template a document version was generated against.
     *
     * <p>Prefers the version's own template id (that is the template
     * that actually shaped this version), then the document's template,
     * and finally the workspace default for the document type.
     */
    private DocTemplate resolveTemplate(Document document, DocumentVersion version) {
        if (version.getTemplateId() != null) {
            DocTemplate t = templateRepository.findById(version.getTemplateId()).orElse(null);
            if (t != null) {
                return t;
            }
        }
        if (document.getTemplateId() != null) {
            DocTemplate t = templateRepository.findById(document.getTemplateId()).orElse(null);
            if (t != null) {
                return t;
            }
        }
        return templateService.resolveDefault(document.getWorkspaceId(), document.getDocType());
    }
}
