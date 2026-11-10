package com.livingdocs.modules.github.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.template.model.AutoGenerateTrigger;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.service.TemplateSchemaResolver;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The template's auto-generation flags decide whether a code change may
 * regenerate a document.
 *
 * <p>Until this gate existed the three {@code auto_generate_on_*} flags
 * were dead metadata: a template that only asked for "on merge" was
 * regenerated on every commit and every pull request as well.
 */
class DocumentAutoUpdateServiceTriggerTest {

    private DocumentAutoUpdateService service;
    private DocumentRepository documentRepository;
    private CodeEntityRepository codeEntityRepository;
    private CodeDocumentLinkRepository linkRepository;
    private GithubClient githubClient;
    private AiDocumentationService aiDocumentationService;
    private ModuleAutoUpdateService moduleAutoUpdateService;
    private TemplateSchemaResolver schemaResolver;

    private UUID workspaceId;
    private UUID documentId;
    private Repository repo;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        documentRepository = mock(DocumentRepository.class);
        codeEntityRepository = mock(CodeEntityRepository.class);
        linkRepository = mock(CodeDocumentLinkRepository.class);
        githubClient = mock(GithubClient.class);
        aiDocumentationService = mock(AiDocumentationService.class);
        moduleAutoUpdateService = mock(ModuleAutoUpdateService.class);
        schemaResolver = mock(TemplateSchemaResolver.class);

        service = new DocumentAutoUpdateService(
                new ObjectMapper(),
                codeEntityRepository,
                linkRepository,
                documentRepository,
                mock(GithubConnectionRepository.class),
                mock(WorkspaceMemberRepository.class),
                githubClient,
                aiDocumentationService,
                moduleAutoUpdateService,
                schemaResolver);
        when(moduleAutoUpdateService.vetoedFiles(any(), any())).thenReturn(Set.of());

        repo = Repository.create(workspaceId, 1L, "acme", "docs", "main",
                "https://github.com/acme/docs", null, false, UUID.randomUUID());

        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getTitle()).thenReturn("Module guide");
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(document.getDocType()).thenReturn("MODULE_GUIDE");
        when(document.isAutoUpdateEnabled()).thenReturn(true);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        CodeEntity entity = mock(CodeEntity.class);
        UUID entityId = UUID.randomUUID();
        when(entity.getId()).thenReturn(entityId);
        when(entity.getFilePath()).thenReturn("src/main/java/Foo.java");
        when(codeEntityRepository.findByFilePathIn(any())).thenReturn(List.of(entity));

        CodeDocumentLink link = mock(CodeDocumentLink.class);
        when(link.getDocumentId()).thenReturn(documentId);
        when(link.getCodeEntityId()).thenReturn(entityId);
        when(linkRepository.findByCodeEntityIdIn(any())).thenReturn(List.of(link));

        when(githubClient.getFileContent(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("class Foo {}");
    }

    private DocTemplate template(boolean onCommit, boolean onPr, boolean onMerge) {
        DocTemplate t = mock(DocTemplate.class);
        when(t.isAutoGenerateOnCommit()).thenReturn(onCommit);
        when(t.isAutoGenerateOnPr()).thenReturn(onPr);
        when(t.isAutoGenerateOnMerge()).thenReturn(onMerge);
        return t;
    }

    private void resolveTo(DocTemplate template) {
        when(schemaResolver.resolveTemplateForDocument(any())).thenReturn(template);
    }

    private int run(AutoGenerateTrigger trigger) {
        return service.triggerAutoUpdate(repo, Set.of("src/main/java/Foo.java"),
                "main", "abc1234", "token", trigger);
    }

    @Test
    void aTemplateThatOnlyAllowsCommitIsNotRegeneratedOnAPullRequest() {
        resolveTo(template(true, false, false));

        assertEquals(0, run(AutoGenerateTrigger.PR));
        verify(aiDocumentationService, never()).generateForDocument(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTemplateThatOnlyAllowsPrIsNotRegeneratedOnACommit() {
        resolveTo(template(false, true, false));

        assertEquals(0, run(AutoGenerateTrigger.COMMIT));
        verify(aiDocumentationService, never()).generateForDocument(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTemplateThatOnlyAllowsMergeIsNotRegeneratedOnACommit() {
        resolveTo(template(false, false, true));

        assertEquals(0, run(AutoGenerateTrigger.COMMIT));
        verify(aiDocumentationService, never()).generateForDocument(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOptedInTriggerRegeneratesTheDocument() {
        resolveTo(template(true, true, true));

        assertEquals(1, run(AutoGenerateTrigger.MERGE));
        verify(aiDocumentationService).generateForDocument(
                eq(null), eq(documentId), any(), any(), eq(null), any(), any(), eq(null));
    }

    @Test
    void aDocumentWithoutATemplateKeepsThePreviousBehaviour() {
        // No template means no flag to satisfy — the document-level and
        // repository-level toggles alone decide.
        when(schemaResolver.resolveTemplateForDocument(any())).thenReturn(null);

        assertEquals(1, run(AutoGenerateTrigger.COMMIT));
        verify(aiDocumentationService).generateForDocument(
                eq(null), eq(documentId), any(), any(), eq(null), any(), any(), eq(null));
    }

    @Test
    void aFailureToResolveTheTemplateDoesNotStopTheRegeneration() {
        when(schemaResolver.resolveTemplateForDocument(any()))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(1, run(AutoGenerateTrigger.COMMIT));
    }
}
