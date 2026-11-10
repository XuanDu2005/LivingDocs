package com.livingdocs.modules.health.service;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.health.dto.ComplianceReportRow;
import com.livingdocs.modules.health.dto.HealthReportRow;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateComplianceEvaluator;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the "Health &amp; Compliance" report of a workspace.
 *
 * <p>The report is the printable form of the health dashboard: the same
 * numbers, but in a shape a manager can send to somebody who has no
 * account — a spreadsheet, a PDF, a ticket.
 *
 * <p>Two views are derived from the same data:
 *
 * <ul>
 *   <li><b>The health report</b> — one row per document, joining
 *       {@code documents}, {@code document_versions},
 *       {@code drift_alerts} and {@code doc_templates}. It answers
 *       "which documents of this workspace are stale, off-template or
 *       drifting?"</li>
 *   <li><b>The compliance report</b> — one row per live version. It
 *       answers "does the documentation that is actually published follow
 *       the template it claims to follow?"</li>
 * </ul>
 *
 * <p>Both are assembled in memory from a bounded number of queries: the
 * documents, the versions of those documents and the drift alerts of
 * the workspace are each loaded in one go. Doing it per document would
 * be a query per document, and a workspace with a thousand documents
 * would take a thousand round trips.
 *
 * <p>The template comparison itself is delegated to
 * {@link TemplateComplianceEvaluator}, the same pure function the
 * review queue uses — so the report and the review badge can never
 * disagree about whether a document follows its template.
 */
@Service
public class HealthReportService {

    /**
     * Upper bound on the number of documents one report may contain.
     *
     * <p>The report is a document-level export; a workspace with a
     * hundred thousand documents would produce a file nobody can open.
     * The cap keeps the export bounded and the memory predictable.
     */
    public static final int MAX_DOCUMENTS = 5_000;

    /**
     * The statuses a version has to be in to count as "live".
     *
     * <p>{@code PUBLISHED} is the version a reader sees;
     * {@code APPROVED} is the one that is about to replace it. Both
     * matter for a compliance report: a version that was approved
     * against a template and is waiting for the publish is exactly the
     * one whose compliance is worth checking before it goes live.
     */
    private static final List<VersionStatus> LIVE_STATUSES =
            List.of(VersionStatus.PUBLISHED, VersionStatus.APPROVED);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final DocTemplateRepository templateRepository;
    private final DocTemplateService templateService;
    private final TemplateComplianceEvaluator evaluator;
    private final WorkspaceService workspaceService;

    public HealthReportService(DocumentRepository documentRepository,
                                DocumentVersionRepository versionRepository,
                                DriftAlertRepository driftAlertRepository,
                                DocTemplateRepository templateRepository,
                                DocTemplateService templateService,
                                TemplateComplianceEvaluator evaluator,
                                WorkspaceService workspaceService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.evaluator = evaluator;
        this.workspaceService = workspaceService;
    }

    // =================================================================
    // The health report
    // =================================================================

    /**
     * One row per document of the workspace.
     *
     * @param actorId     the caller; has to be a member of the workspace
     * @param workspaceId the workspace the report belongs to
     */
    @Transactional(readOnly = true)
    public List<HealthReportRow> healthReport(UUID actorId, UUID workspaceId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        List<Document> documents = documentsOf(workspaceId);
        if (documents.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<DocumentVersion>> versionsByDocument =
                versionsOf(documents);
        Map<UUID, DriftStats> driftByDocument = driftStatsOf(workspaceId);
        Map<String, DocTemplate> defaultsByDocType = defaultTemplatesByDocType(workspaceId, documents);
        Map<UUID, DocTemplate> templatesById = templatesById(documents, versionsByDocument);

        OffsetDateTime now = OffsetDateTime.now();
        List<HealthReportRow> rows = new ArrayList<>(documents.size());
        for (Document document : documents) {
            List<DocumentVersion> versions =
                    versionsByDocument.getOrDefault(document.getId(), List.of());
            DocumentVersion latest = versions.isEmpty() ? null : versions.get(0);
            // The version a reader actually sees: the head of the
            // document. When the head is unknown (a document that was
            // never published) the newest version is the best guess.
            DocumentVersion head = headVersion(document, versions);

            DocTemplate template = resolveTemplate(document, head, templatesById, defaultsByDocType);
            TemplateComplianceEvaluator.Result compliance =
                    evaluator.evaluate(template, head == null ? null : head.getBodyMarkdown());

            DriftStats drift = driftByDocument.getOrDefault(document.getId(), DriftStats.EMPTY);

            rows.add(new HealthReportRow(
                    document.getId(),
                    document.getTitle(),
                    document.getSlug(),
                    document.getDocType(),
                    document.getStatus() == null ? null : document.getStatus().name(),
                    document.getOwnerId(),
                    template == null ? null : template.getName(),
                    template == null ? null : compliance.compliant(),
                    template == null ? List.of() : compliance.missingHeadings(),
                    latest == null ? null : latest.getVersionNumber(),
                    format(document.getUpdatedAt()),
                    drift.openCount(),
                    drift.maxSeverity() == null ? null : drift.maxSeverity().name(),
                    daysSince(document.getUpdatedAt(), now)));
        }
        return rows;
    }

    // =================================================================
    // The compliance report
    // =================================================================

    /**
     * One row per live version of every document of the workspace.
     *
     * @param actorId     the caller; has to be a member of the workspace
     * @param workspaceId the workspace the report belongs to
     */
    @Transactional(readOnly = true)
    public List<ComplianceReportRow> complianceReport(UUID actorId, UUID workspaceId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        List<Document> documents = documentsOf(workspaceId);
        if (documents.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<DocumentVersion>> versionsByDocument = versionsOf(documents);
        Map<String, DocTemplate> defaultsByDocType = defaultTemplatesByDocType(workspaceId, documents);
        Map<UUID, DocTemplate> templatesById = templatesById(documents, versionsByDocument);

        List<ComplianceReportRow> rows = new ArrayList<>();
        for (Document document : documents) {
            List<DocumentVersion> versions =
                    versionsByDocument.getOrDefault(document.getId(), List.of());
            for (DocumentVersion version : versions) {
                if (version.getStatus() == null || !LIVE_STATUSES.contains(version.getStatus())) {
                    continue;
                }
                DocTemplate template =
                        resolveTemplate(document, version, templatesById, defaultsByDocType);
                TemplateComplianceEvaluator.Result compliance =
                        evaluator.evaluate(template, version.getBodyMarkdown());

                rows.add(new ComplianceReportRow(
                        document.getId(),
                        version.getVersionNumber(),
                        template == null ? null : template.getName(),
                        template == null ? null : compliance.compliant(),
                        template == null ? List.of() : compliance.missingHeadings(),
                        template == null ? List.of() : compliance.outOfOrderHeadings(),
                        version.getActorRole() == null ? null : version.getActorRole().name(),
                        format(version.getCreatedAt())));
            }
        }
        return rows;
    }

    // =================================================================
    // Assembly helpers
    // =================================================================

    /**
     * The documents of the workspace, newest first and capped.
     */
    private List<Document> documentsOf(UUID workspaceId) {
        List<Document> documents =
                documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        if (documents.size() > MAX_DOCUMENTS) {
            documents = documents.subList(0, MAX_DOCUMENTS);
        }
        return documents;
    }

    /**
     * Every version of every document of the workspace, grouped by
     * document and ordered newest version first.
     *
     * <p>Loaded in one query: a report over a thousand documents would
     * otherwise cost a thousand queries.
     */
    private Map<UUID, List<DocumentVersion>> versionsOf(List<Document> documents) {
        List<UUID> documentIds = documents.stream().map(Document::getId).toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentVersion> versions =
                versionRepository.findAllByDocumentIdInOrderByVersionNumberDesc(documentIds);
        Map<UUID, List<DocumentVersion>> byDocument = new LinkedHashMap<>();
        for (DocumentVersion version : versions) {
            byDocument.computeIfAbsent(version.getDocumentId(), id -> new ArrayList<>())
                    .add(version);
        }
        return byDocument;
    }

    /**
     * The open drift alerts of the workspace, aggregated per document.
     *
     * <p>Only the <em>open</em> alerts count: a drift that was already
     * fixed or dismissed is not a health problem any more, and counting
     * it would make the report permanently pessimistic.
     */
    private Map<UUID, DriftStats> driftStatsOf(UUID workspaceId) {
        List<DriftAlert> alerts = driftAlertRepository
                .findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                        workspaceId, DriftResolution.OPEN);
        Map<UUID, DriftStats> byDocument = new HashMap<>();
        for (DriftAlert alert : alerts) {
            byDocument.merge(alert.getDocumentId(),
                    DriftStats.of(alert.getSeverity()),
                    DriftStats::merge);
        }
        return byDocument;
    }

    /**
     * The default template of every documentation type of the
     * workspace.
     *
     * <p>Resolved once per type instead of once per document: the
     * fallback resolution is two queries per call, and a workspace with
     * a hundred documents of the same type would pay it a hundred
     * times.
     */
    private Map<String, DocTemplate> defaultTemplatesByDocType(UUID workspaceId,
                                                              List<Document> documents) {
        Map<String, DocTemplate> defaults = new HashMap<>();
        for (Document document : documents) {
            if (defaults.containsKey(document.getDocType())) {
                continue;
            }
            DocTemplate template =
                    templateService.resolveDefault(workspaceId, document.getDocType());
            if (template != null) {
                defaults.put(document.getDocType(), template);
            }
        }
        return defaults;
    }

    /**
     * The templates the documents and their versions point at, keyed by
     * id.
     *
     * <p>Loaded in one query for the same reason as the versions.
     */
    private Map<UUID, DocTemplate> templatesById(List<Document> documents,
                                                 Map<UUID, List<DocumentVersion>> versionsByDocument) {
        List<UUID> templateIds = new ArrayList<>();
        for (Document document : documents) {
            if (document.getTemplateId() != null) {
                templateIds.add(document.getTemplateId());
            }
        }
        for (List<DocumentVersion> versions : versionsByDocument.values()) {
            for (DocumentVersion version : versions) {
                if (version.getTemplateId() != null) {
                    templateIds.add(version.getTemplateId());
                }
            }
        }
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, DocTemplate> byId = new HashMap<>();
        for (DocTemplate template : templateRepository.findAllById(templateIds.stream().distinct().toList())) {
            byId.put(template.getId(), template);
        }
        return byId;
    }

    /**
     * The template a document (or one of its versions) follows.
     *
     * <p>The order is the same as the one the review module uses: the
     * version's own template first (that is the template that actually
     * shaped this version), then the document's template, then the
     * workspace default of the document type.
     */
    private DocTemplate resolveTemplate(Document document,
                                        DocumentVersion version,
                                        Map<UUID, DocTemplate> templatesById,
                                        Map<String, DocTemplate> defaultsByDocType) {
        if (version != null && version.getTemplateId() != null) {
            DocTemplate template = templatesById.get(version.getTemplateId());
            if (template != null) {
                return template;
            }
        }
        if (document.getTemplateId() != null) {
            DocTemplate template = templatesById.get(document.getTemplateId());
            if (template != null) {
                return template;
            }
        }
        return defaultsByDocType.get(document.getDocType());
    }

    /**
     * The version a reader of the document sees.
     *
     * <p>The head is the published version. When the document carries
     * no head — it was never published — the newest version is used, so
     * that the report still says something about the document instead
     * of showing empty cells.
     */
    private DocumentVersion headVersion(Document document, List<DocumentVersion> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        if (document.getHeadVersionId() != null) {
            for (DocumentVersion version : versions) {
                if (document.getHeadVersionId().equals(version.getId())) {
                    return version;
                }
            }
        }
        return versions.get(0);
    }

    // =================================================================
    // Small value helpers
    // =================================================================

    /**
     * How many days ago the given instant was, relative to now.
     *
     * <p>Never negative: a document with an update timestamp in the
     * future (a clock skew between the database and the application
     * server) must not produce a negative age.
     */
    static Long daysSince(OffsetDateTime then, OffsetDateTime now) {
        if (then == null) {
            return null;
        }
        long days = Duration.between(then, now).toDays();
        return Math.max(0L, days);
    }

    /**
     * The ISO-8601 representation of an instant, or the empty string.
     */
    static String format(OffsetDateTime instant) {
        return instant == null ? "" : instant.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * The aggregated drift state of one document.
     *
     * @param openCount   the number of open drift alerts
     * @param maxSeverity the worst severity among them, or {@code null}
     */
    private record DriftStats(int openCount, DriftSeverity maxSeverity) {

        static final DriftStats EMPTY = new DriftStats(0, null);

        static DriftStats of(DriftSeverity severity) {
            return new DriftStats(1, severity);
        }

        /**
         * Merge another alert into this state.
         *
         * <p>The worst severity wins — "how bad is it" is a question
         * about the maximum, not about the average.
         */
        DriftStats merge(DriftStats other) {
            DriftSeverity worst = maxSeverity;
            if (other.maxSeverity() != null
                    && (worst == null || other.maxSeverity().compareTo(worst) > 0)) {
                worst = other.maxSeverity();
            }
            return new DriftStats(openCount + other.openCount(), worst);
        }
    }
}
