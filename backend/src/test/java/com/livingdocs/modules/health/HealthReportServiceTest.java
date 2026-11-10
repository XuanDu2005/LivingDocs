package com.livingdocs.modules.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.health.dto.ComplianceReportRow;
import com.livingdocs.modules.health.dto.HealthReportRow;
import com.livingdocs.modules.health.service.HealthReportService;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateComplianceEvaluator;
import com.livingdocs.modules.template.service.TemplateSchema;
import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The health &amp; compliance report.
 *
 * <p>The report is the printable form of the health dashboard. These
 * tests pin the rules that make it trustworthy: the drift counters only
 * count the <em>open</em> alerts, the worst severity wins over the
 * others, and a document without a template is reported as "no
 * template" rather than as "compliant".
 */
class HealthReportServiceTest {

    private DocumentRepository documentRepository;
    private DocumentVersionRepository versionRepository;
    private DriftAlertRepository driftAlertRepository;
    private DocTemplateRepository templateRepository;
    private DocTemplateService templateService;
    private WorkspaceService workspaceService;
    private HealthReportService service;

    private UUID workspaceId;
    private UUID actorId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        versionRepository = mock(DocumentVersionRepository.class);
        driftAlertRepository = mock(DriftAlertRepository.class);
        templateRepository = mock(DocTemplateRepository.class);
        templateService = mock(DocTemplateService.class);
        workspaceService = mock(WorkspaceService.class);

        TemplateComplianceEvaluator evaluator = new TemplateComplianceEvaluator(
                new TemplateSchema(new ObjectMapper()), new ObjectMapper());

        service = new HealthReportService(
                documentRepository,
                versionRepository,
                driftAlertRepository,
                templateRepository,
                templateService,
                evaluator,
                workspaceService);

        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of());
        when(versionRepository.findAllByDocumentIdInOrderByVersionNumberDesc(any()))
                .thenReturn(List.of());
        when(driftAlertRepository.findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                any(), any())).thenReturn(List.of());
        when(templateRepository.findAllById(any())).thenReturn(List.of());
        when(templateService.resolveDefault(any(), any())).thenReturn(null);
    }

    /**
     * A document of the workspace under test.
     */
    private Document document(DocumentStatus status, OffsetDateTime updatedAt) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(document.getTitle()).thenReturn("API reference");
        when(document.getSlug()).thenReturn("api-reference");
        when(document.getDocType()).thenReturn("API_REFERENCE");
        when(document.getStatus()).thenReturn(status);
        when(document.getOwnerId()).thenReturn(UUID.randomUUID());
        when(document.getUpdatedAt()).thenReturn(updatedAt);
        return document;
    }

    /**
     * A drift alert of the document under test.
     */
    private DriftAlert alert(DriftSeverity severity) {
        DriftAlert alert = mock(DriftAlert.class);
        when(alert.getDocumentId()).thenReturn(documentId);
        when(alert.getSeverity()).thenReturn(severity);
        when(alert.getResolutionStatus()).thenReturn(DriftResolution.OPEN);
        return alert;
    }

    /**
     * A version of the document under test.
     */
    private DocumentVersion version(int number, VersionStatus status) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getDocumentId()).thenReturn(documentId);
        when(version.getVersionNumber()).thenReturn(number);
        when(version.getStatus()).thenReturn(status);
        when(version.getActorRole()).thenReturn(ActorRole.AI);
        when(version.getBodyMarkdown()).thenReturn("# Overview\n\ntext\n");
        when(version.getCreatedAt()).thenReturn(OffsetDateTime.now());
        return version;
    }

    /**
     * A workspace without documents produces an empty report rather
     * than an error.
     */
    @Test
    void anEmptyWorkspaceProducesAnEmptyReport() {
        assertTrue(service.healthReport(actorId, workspaceId).isEmpty());
        assertTrue(service.complianceReport(actorId, workspaceId).isEmpty());
    }

    /**
     * The report carries the document metadata the requirement names.
     */
    @Test
    void theRowCarriesTheDocumentMetadata() {
        OffsetDateTime updatedAt = OffsetDateTime.now().minusDays(3);
        Document document = document(DocumentStatus.PUBLISHED, updatedAt);
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        List<HealthReportRow> rows = service.healthReport(actorId, workspaceId);

        assertEquals(1, rows.size());
        HealthReportRow row = rows.get(0);
        assertEquals(documentId, row.documentId());
        assertEquals("API reference", row.title());
        assertEquals("api-reference", row.slug());
        assertEquals("API_REFERENCE", row.docType());
        assertEquals("PUBLISHED", row.status());
        assertNotNull(row.ownerId());
        assertEquals(3L, row.daysSinceUpdate());
    }

    /**
     * A document that has no template is reported as "no template" —
     * not as "compliant". Conflating the two would tell the reader
     * that a document follows a template it does not have.
     */
    @Test
    void aDocumentWithoutATemplateIsNotReportedAsCompliant() {
        Document document = document(DocumentStatus.DRAFT, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        HealthReportRow row = service.healthReport(actorId, workspaceId).get(0);

        assertNull(row.templateName());
        assertNull(row.templateCompliant());
        assertTrue(row.missingHeadings().isEmpty());
    }

    /**
     * The drift counter only counts the <em>open</em> alerts, and the
     * worst severity wins.
     */
    @Test
    void theWorstOpenDriftSeverityIsReported() {
        Document document = document(DocumentStatus.PUBLISHED, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        List<DriftAlert> alerts = List.of(
                alert(DriftSeverity.LOW),
                alert(DriftSeverity.CRITICAL),
                alert(DriftSeverity.MEDIUM));
        when(driftAlertRepository.findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                eq(workspaceId), any())).thenReturn(alerts);

        HealthReportRow row = service.healthReport(actorId, workspaceId).get(0);

        assertEquals(3, row.openDrift());
        assertEquals("CRITICAL", row.maxDriftSeverity());
    }

    /**
     * A workspace without drift reports no alerts rather than a null.
     */
    @Test
    void aDocumentWithoutDriftReportsZeroAlerts() {
        Document document = document(DocumentStatus.PUBLISHED, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        HealthReportRow row = service.healthReport(actorId, workspaceId).get(0);

        assertEquals(0, row.openDrift());
        assertNull(row.maxDriftSeverity());
    }

    /**
     * The compliance report only contains the versions that are live —
     * the ones a reader of the documentation actually sees.
     */
    @Test
    void theComplianceReportOnlyContainsLiveVersions() {
        Document document = document(DocumentStatus.PUBLISHED, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        List<DocumentVersion> versions = List.of(
                version(4, VersionStatus.DRAFT),
                version(3, VersionStatus.PUBLISHED));
        when(versionRepository.findAllByDocumentIdInOrderByVersionNumberDesc(any()))
                .thenReturn(versions);

        List<ComplianceReportRow> rows = service.complianceReport(actorId, workspaceId);

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).versionNumber());
        assertEquals(documentId, rows.get(0).documentId());
    }

    /**
     * The compliance report names the role that produced the version.
     */
    @Test
    void theComplianceReportNamesTheProducingRole() {
        Document document = document(DocumentStatus.PUBLISHED, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        List<DocumentVersion> versions = List.of(version(1, VersionStatus.PUBLISHED));
        when(versionRepository.findAllByDocumentIdInOrderByVersionNumberDesc(any()))
                .thenReturn(versions);

        ComplianceReportRow row = service.complianceReport(actorId, workspaceId).get(0);

        assertEquals("AI", row.generatedBy());
    }

    /**
     * An approved version is included too: it is the one that is about
     * to go live, and its compliance is worth checking before it does.
     */
    @Test
    void anApprovedVersionIsPartOfTheComplianceReport() {
        Document document = document(DocumentStatus.APPROVED, OffsetDateTime.now());
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        List<DocumentVersion> versions = List.of(version(2, VersionStatus.APPROVED));
        when(versionRepository.findAllByDocumentIdInOrderByVersionNumberDesc(any()))
                .thenReturn(versions);

        assertEquals(1, service.complianceReport(actorId, workspaceId).size());
    }

    /**
     * The age of a document is never negative: a clock skew between
     * the database and the application server must not produce a
     * negative number of days.
     */
    @Test
    void theAgeOfADocumentIsNeverNegative() {
        Document document = document(DocumentStatus.PUBLISHED, OffsetDateTime.now().plusDays(2));
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));

        HealthReportRow row = service.healthReport(actorId, workspaceId).get(0);

        assertEquals(0L, row.daysSinceUpdate());
    }
}
