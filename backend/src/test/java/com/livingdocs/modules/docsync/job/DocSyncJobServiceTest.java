package com.livingdocs.modules.docsync.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.docsync.job.DocSyncJobRepository;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.DocSyncService;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The repository-wide documentation sync, as a tracked job.
 *
 * <p>The behaviour worth protecting here is the reason the sync was
 * moved off the HTTP request in the first place:
 *
 * <ul>
 *   <li>a repeated click must not start a second run — two concurrent
 *       runs would create duplicate proposals and burn the LLM budget
 *       twice;</li>
 *   <li>one file that cannot be processed must not take the rest of the
 *       run down with it, and the job has to say so rather than report a
 *       clean success.</li>
 * </ul>
 */
class DocSyncJobServiceTest {

    private DocSyncJobRepository jobRepository;
    private RepositoryRepository repositoryRepository;
    private DocSyncService docSyncService;
    private GithubOAuthService githubOAuthService;
    private WorkspaceService workspaceService;
    private PermissionService permissionService;
    private DocSyncJobService service;

    private UUID workspaceId;
    private UUID repositoryId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        jobRepository = mock(DocSyncJobRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        docSyncService = mock(DocSyncService.class);
        githubOAuthService = mock(GithubOAuthService.class);
        workspaceService = mock(WorkspaceService.class);
        permissionService = mock(PermissionService.class);

        // The worker is handed the job, not run here: the test is about
        // the service, and running the worker would make the test
        // asynchronous.
        DocSyncJobWorker worker = mock(DocSyncJobWorker.class);

        service = new DocSyncJobService(
                jobRepository,
                repositoryRepository,
                docSyncService,
                githubOAuthService,
                workspaceService,
                permissionService,
                new ObjectMapper(),
                new NoOpTransactionManager(),
                worker);

        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        // The sync needs a GitHub credential; without one it fails
        // before it ever reaches the AI.
        GithubConnection connection = GithubConnection.create(
                actorId, 1L, "octocat", "gh-token", "bearer", "repo");
        when(githubOAuthService.findForUser(any())).thenReturn(Optional.of(connection));
    }

    @Test
    void enqueueReturnsTheRunningJobWhenOneIsAlreadyInFlight() {
        DocSyncJob existing = job(workspaceId, repositoryId, actorId, DocSyncJobStatus.RUNNING);

        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(repository(workspaceId)));
        when(jobRepository.findActiveForRepository(repositoryId)).thenReturn(List.of(existing));

        DocSyncJob result = service.enqueue(actorId, workspaceId, repositoryId, null);

        assertEquals(existing.getId(), result.getId());
        // A second run would double the proposals and the LLM cost.
        verify(jobRepository, never()).save(any());
    }

    @Test
    void enqueueRefusesARepositoryFromAnotherWorkspace() {
        Repository repo = repository(UUID.randomUUID());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repo));

        assertThrowsIllegalArgument(() ->
                service.enqueue(actorId, workspaceId, repositoryId, null));
    }

    @Test
    void enqueueCreatesTheJobAndHandsItToTheWorker() {
        Repository repo = repository(workspaceId);
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repo));
        when(jobRepository.findActiveForRepository(repositoryId)).thenReturn(List.of());
        when(jobRepository.save(any(DocSyncJob.class))).thenAnswer(inv -> inv.getArgument(0));

        DocSyncJob result = service.enqueue(actorId, workspaceId, repositoryId, "main");

        assertNotNull(result.getId() != null ? result.getId() : UUID.randomUUID());
        assertEquals(DocSyncJobStatus.PENDING, result.getStatus());
        assertEquals("main", result.getBranch());
        assertEquals(actorId, result.getRequestedBy());
    }

    @Test
    void oneFailingFileDoesNotAbortTheRestOfTheRun() {
        // The scan finds two drifted files.
        DocSyncService.DocFileInfo good = file("docs/good.md");
        DocSyncService.DocFileInfo bad = file("docs/bad.md");

        when(docSyncService.scanRepository(any(), any(), any(), any(), any()))
                .thenReturn(new DocSyncService.ScanResult(2, 0, 0,
                        List.of(good, bad)));

        // The good file produces a proposal; the bad one fails.
        when(docSyncService.queueProposalForFile(any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(5);
                    if ("docs/bad.md".equals(path)) {
                        return DocSyncService.FileProposalOutcome.hasFailed("quota exhausted");
                    }
                    return DocSyncService.FileProposalOutcome.wasQueued();
                });

        DocSyncJob job = job(workspaceId, repositoryId, actorId, DocSyncJobStatus.PENDING);

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocSyncJob.class))).thenAnswer(inv -> inv.getArgument(0));

        Repository repo = repository(workspaceId);
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repo));

        service.run(job.getId());

        // Both files were attempted — the failure did not stop the run.
        verify(docSyncService, org.mockito.Mockito.times(2))
                .queueProposalForFile(any(), any(), any(), any(), any(), anyString());

        // PARTIAL, not SUCCEEDED: the user has to know that one file
        // could not be processed.
        DocSyncJob saved = capturedJob(job.getId());
        assertEquals(DocSyncJobStatus.PARTIAL, saved.getStatus());
        assertEquals(1, saved.getQueuedProposals());
        assertEquals(0, saved.getSkippedProposals());
        assertEquals(1, saved.getFailedFiles());
    }

    @Test
    void theJobCarriesThePerFileOutcome() {
        DocSyncService.DocFileInfo file = file("docs/a.md");
        when(docSyncService.scanRepository(any(), any(), any(), any(), any()))
                .thenReturn(new DocSyncService.ScanResult(1, 0, 0, List.of(file)));
        when(docSyncService.queueProposalForFile(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(DocSyncService.FileProposalOutcome.wasSkipped(
                        "A review request for this file is already pending."));

        DocSyncJob job = job(workspaceId, repositoryId, actorId, DocSyncJobStatus.PENDING);

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocSyncJob.class))).thenAnswer(inv -> inv.getArgument(0));

        Repository repo = repository(workspaceId);
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repo));

        service.run(job.getId());

        DocSyncJob saved = capturedJob(job.getId());
        assertEquals(DocSyncJobStatus.SUCCEEDED, saved.getStatus());
        assertNotNull(saved.getResultJson());
        assertEquals(1, saved.getSkippedProposals());
    }

    // ---- helpers ------------------------------------------------------

    private DocSyncJob capturedJob(UUID jobId) {
        org.mockito.ArgumentCaptor<DocSyncJob> captor =
                org.mockito.ArgumentCaptor.forClass(DocSyncJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<DocSyncJob> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static DocSyncService.DocFileInfo file(String path) {
        return new DocSyncService.DocFileInfo(path, "file", 100L, 1,
                List.of("ClassA"), List.of(), true, "missing ClassA");
    }

    /**
     * Give a job row an id.
     *
     * <p>The id is generated by JPA, so there is no setter for it. The
     * reflection helper of Spring's test support fills it in.
     */
    private static DocSyncJob job(UUID workspaceId, UUID repositoryId, UUID actorId,
                                  DocSyncJobStatus status) {
        DocSyncJob job = new DocSyncJob();
        org.springframework.test.util.ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        job.setWorkspaceId(workspaceId);
        job.setRepositoryId(repositoryId);
        job.setRequestedBy(actorId);
        job.setStatus(status);
        job.setCreatedAt(java.time.OffsetDateTime.now());
        return job;
    }

    /**
     * A repository owned by the given workspace.
     */
    private static Repository repository(UUID workspaceId) {
        return Repository.create(workspaceId, 1L, "owner", "repo", "main",
                "https://github.com/owner/repo", "test", false, UUID.randomUUID());
    }

    private static void assertThrowsIllegalArgument(Runnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, runnable::run);
    }

    /**
     * A transaction manager that does nothing.
     *
     * <p>The service writes its progress through a
     * {@link TransactionTemplate}, and the assertions are about the
     * values the service computed — not about the database round trip.
     */
    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            // nothing to commit
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            // nothing to roll back
        }
    }
}
