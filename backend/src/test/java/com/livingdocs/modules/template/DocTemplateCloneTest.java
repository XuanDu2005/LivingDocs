package com.livingdocs.modules.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.model.OutputFormat;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateSchema;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The clone flow: a global template has to be cloneable INTO a workspace.
 */
class DocTemplateCloneTest {

    private DocTemplateService service;
    private DocTemplateRepository templateRepo;
    private WorkspaceService workspaceService;
    private JdbcTemplate jdbc;

    private UUID actorId;
    private UUID workspaceId;

    /** The row the service is cloning from. */
    private DocTemplate source;
    /** The row the service reads back after the INSERT. */
    private DocTemplate saved;

    @BeforeEach
    void setUp() {
        templateRepo = mock(DocTemplateRepository.class);
        workspaceService = mock(WorkspaceService.class);
        jdbc = mock(JdbcTemplate.class);

        service = new DocTemplateService(
                templateRepo,
                workspaceService,
                new ObjectMapper(),
                mock(TemplateSchema.class),
                jdbc);
        ReflectionTestUtils.setField(service, "em", mock(EntityManager.class));

        actorId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        saved = new DocTemplate(workspaceId, "Clone", "clone", "desc",
                "API_REFERENCE", 1, "{\"sections\":[]}", false, actorId);
        saved.setOutputFormat(OutputFormat.MARKDOWN);

        // The source row and the post-INSERT row live in different slots;
        // the service reads the source first and the saved row last.
        when(templateRepo.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.of(saved));
        // Every slug lookup misses, so the clone keeps the base slug.
        when(templateRepo.findBySlugAndVersion(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void clone_globalTemplate_createsAWorkspaceScopedCopy() throws Exception {
        UUID sourceId = UUID.randomUUID();
        source = globalTemplate();
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));

        // The INSERT must land in the target workspace, not in a null
        // (global) workspace.
        service.clone(actorId, workspaceId, sourceId, "My API Reference");

        PreparedStatement statement = insertedStatement();
        verify(statement).setObject(2, workspaceId);
        verify(statement).setString(3, "My API Reference");
        verify(statement).setInt(7, 1);
        // A cloned template never inherits the "default" flag.
        verify(statement).setBoolean(13, false);
    }

    @Test
    void clone_globalTemplate_requiresManagerInTheTargetWorkspace() {
        UUID sourceId = UUID.randomUUID();
        source = globalTemplate();
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(workspaceService.requireDocumentationGovernance(actorId, workspaceId))
                .thenThrow(new ForbiddenException("Insufficient permissions in this workspace"));

        assertThrows(ForbiddenException.class,
                () -> service.clone(actorId, workspaceId, sourceId, "My API Reference"));
    }

    @Test
    void clone_globalTemplateWithoutTargetWorkspace_usesTheFirstManagedOne() {
        UUID sourceId = UUID.randomUUID();
        source = globalTemplate();
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(workspaceService.findFirstManagedWorkspaceId(actorId)).thenReturn(workspaceId);

        service.clone(actorId, null, sourceId, "My API Reference");

        verify(workspaceService).requireDocumentationGovernance(actorId, workspaceId);
    }

    @Test
    void clone_globalTemplateWithoutTargetWorkspaceAndNoManagedWorkspace_isRejected() {
        UUID sourceId = UUID.randomUUID();
        source = globalTemplate();
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(workspaceService.findFirstManagedWorkspaceId(actorId)).thenReturn(null);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.clone(actorId, null, sourceId, "My API Reference"));
        assertTrue(ex.getMessage().contains("Clone a global template into a workspace you manage"));
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));
    }

    @Test
    void clone_workspaceTemplate_staysInItsOwnWorkspace() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID sourceWorkspace = UUID.randomUUID();
        source = workspaceTemplate(sourceWorkspace);
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));

        service.clone(actorId, sourceWorkspace, sourceId, "Copy");

        verify(workspaceService).requireDocumentationGovernance(actorId, sourceWorkspace);
        verify(insertedStatement()).setObject(2, sourceWorkspace);
    }

    @Test
    void clone_workspaceTemplateFromAnotherWorkspace_isRejected() {
        UUID sourceId = UUID.randomUUID();
        UUID sourceWorkspace = UUID.randomUUID();
        source = workspaceTemplate(sourceWorkspace);
        when(templateRepo.findById(sourceId)).thenReturn(Optional.of(source));

        assertThrows(ForbiddenException.class,
                () -> service.clone(actorId, workspaceId, sourceId, "Copy"));
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));
    }

    // ---------------- helpers ----------------

    /**
     * The service binds its INSERT through a {@link PreparedStatementSetter},
     * so the assertions have to look at the values it actually set on the
     * statement rather than at an argument array.
     */
    private PreparedStatement insertedStatement() throws Exception {
        ArgumentCaptor<PreparedStatementSetter> captor =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).update(anyString(), captor.capture());
        PreparedStatement statement = mock(PreparedStatement.class);
        captor.getValue().setValues(statement);
        return statement;
    }

    private DocTemplate globalTemplate() {
        DocTemplate t = new DocTemplate(null, "API Reference", "default-api-reference",
                "desc", "API_REFERENCE", 1, "{\"sections\":[]}", false, actorId);
        t.setOutputFormat(OutputFormat.MARKDOWN);
        return t;
    }

    private DocTemplate workspaceTemplate(UUID wsId) {
        DocTemplate t = new DocTemplate(wsId, "API Reference", "my-api-reference",
                "desc", "API_REFERENCE", 1, "{\"sections\":[]}", false, actorId);
        t.setOutputFormat(OutputFormat.MARKDOWN);
        return t;
    }
}
