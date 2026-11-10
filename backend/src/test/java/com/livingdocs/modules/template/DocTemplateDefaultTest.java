package com.livingdocs.modules.template;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The "one default per (workspace, docType)" invariant.
 *
 * <p>{@code resolveDefault()} used to pick an arbitrary row when two
 * templates of the same type both carried the default flag. The
 * {@code setDefault} operation now demotes every other holder of the flag
 * in the same transaction, and the partial unique indexes from V49 make the
 * violation impossible at the database level.
 */
class DocTemplateDefaultTest {

    private DocTemplateService service;
    private DocTemplateRepository templateRepo;
    private WorkspaceService workspaceService;
    private JdbcTemplate jdbc;

    private UUID actorId;
    private UUID workspaceId;

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
    }

    @Test
    void setDefault_demotesTheOtherDefaultOfTheSameDocType() {
        UUID targetId = UUID.randomUUID();
        when(templateRepo.findById(targetId)).thenReturn(Optional.of(template(targetId)));

        service.setDefault(actorId, targetId);

        // The other default for (workspace, docType) is demoted first,
        // and the target id is excluded from that statement.
        verify(jdbc).update(
                eq("UPDATE doc_templates SET is_default = FALSE, updated_at = NOW() "
                        + "WHERE workspace_id = ? AND doc_type = ? AND is_default = TRUE "
                        + "AND id <> ?"),
                eq(workspaceId),
                eq("API_REFERENCE"),
                eq(targetId));

        // ...and only then the target is promoted.
        verify(jdbc).update(
                eq("UPDATE doc_templates SET is_default = TRUE, updated_at = NOW() WHERE id = ?"),
                eq(targetId));
    }

    @Test
    void setDefault_requiresManagerInTheOwningWorkspace() {
        UUID targetId = UUID.randomUUID();
        when(templateRepo.findById(targetId)).thenReturn(Optional.of(template(targetId)));
        when(workspaceService.requireDocumentationGovernance(actorId, workspaceId))
                .thenThrow(new ForbiddenException("Insufficient permissions in this workspace"));

        assertThrows(ForbiddenException.class, () -> service.setDefault(actorId, targetId));

        // Nothing was written.
        verifyNoInteractions(jdbc);
    }

    @Test
    void setDefault_rejectsGlobalTemplates() {
        UUID targetId = UUID.randomUUID();
        DocTemplate global = new DocTemplate(null, "API Reference", "default-api-reference",
                "desc", "API_REFERENCE", 1, "{\"sections\":[]}", false, actorId);
        global.setOutputFormat(OutputFormat.MARKDOWN);
        when(templateRepo.findById(targetId)).thenReturn(Optional.of(global));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.setDefault(actorId, targetId));
        assertTrue(ex.getMessage().contains("admin endpoint"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void setDefault_doesNotDemoteADifferentDocType() {
        UUID targetId = UUID.randomUUID();
        when(templateRepo.findById(targetId)).thenReturn(Optional.of(template(targetId)));

        service.setDefault(actorId, targetId);

        // Every demote statement is scoped by (workspace_id, doc_type), so
        // the default of another type is never affected. Capture all SQL
        // that was executed and assert none of them drops the doc_type
        // filter.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(1)).update(sql.capture(), any(Object[].class));
        for (String statement : sql.getAllValues()) {
            if (statement.contains("SET is_default = FALSE")) {
                assertTrue(statement.contains("doc_type = ?"),
                        "Demote statement must be scoped by doc_type: " + statement);
            }
        }
    }

    // ---------------- helpers ----------------

    private DocTemplate template(UUID id) {
        DocTemplate t = new DocTemplate(workspaceId, "API Reference", "my-api-reference",
                "desc", "API_REFERENCE", 1, "{\"sections\":[]}", false, actorId);
        t.setOutputFormat(OutputFormat.MARKDOWN);
        return t;
    }
}
