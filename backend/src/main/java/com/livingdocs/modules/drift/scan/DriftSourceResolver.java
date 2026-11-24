package com.livingdocs.modules.drift.scan;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequestFile;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubCredentialService;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out <em>what to compare</em> for one document of a repository.
 *
 * <p>The drift detector needs two revisions of the same source file: a
 * "before" and an "after". The document-scoped flow gets them from a
 * pull request, which carries exactly that. The repository-scoped flow
 * has to work them out for itself, and the anchor depends on what the
 * document already knows:
 *
 * <ul>
 *   <li><b>With a pull request</b> — the "before" is the base branch
 *       and the "after" is the head branch, and the compared paths are
 *       the ones the pull request touches.</li>
 *   <li><b>Without one</b> — the "before" is the commit the document
 *       was last written against (its version carries the SHA), and the
 *       "after" is the current state of the default branch. That is the
 *       question the feature is really for: "has the code moved on
 *       since this documentation was written?"</li>
 * </ul>
 *
 * <p>The compared paths are the files the document actually talks
 * about. They come from the {@code code_document_links} table, which
 * records which code entity each document documents. A document with no
 * links — a repository-level overview, for instance — falls back to the
 * files of every indexed entity of the repository, because such a
 * document describes the repository as a whole.
 */
@Service
public class DriftSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(DriftSourceResolver.class);

    /** How many files are compared for one document at most. */
    static final int MAX_FILES_PER_DOCUMENT = 20;

    /** How many characters of one file are handed to the analyser. */
    private static final int MAX_FILE_CHARS = 60_000;

    /**
     * How long a fetched file revision stays cached.
     *
     * <p>Long enough to serve one repository-wide scan, short enough
     * that a later scan sees the current state of the code.
     */
    private static final long CONTENT_CACHE_TTL_MS = 5 * 60_000L;

    /** How many file revisions the cache holds before it is emptied. */
    private static final int CONTENT_CACHE_MAX_ENTRIES = 500;

    /**
     * Recently fetched file revisions, keyed by repository, revision
     * and path.
     */
    private final Map<String, CachedFile> contentCache = new ConcurrentHashMap<>();

    private final GithubClient githubClient;
    private final CodeEntityRepository codeEntityRepository;
    private final CodeDocumentLinkRepository linkRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final CommitRepository commitRepository;
    private final GithubCredentialService credentialService;

    public DriftSourceResolver(GithubClient githubClient,
                               CodeEntityRepository codeEntityRepository,
                               CodeDocumentLinkRepository linkRepository,
                               DocumentVersionRepository versionRepository,
                               DocumentRepository documentRepository,
                               RepositoryRepository repositoryRepository,
                               PullRequestRepository pullRequestRepository,
                               CommitRepository commitRepository,
                               GithubCredentialService credentialService) {
        this.githubClient = githubClient;
        this.codeEntityRepository = codeEntityRepository;
        this.linkRepository = linkRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.commitRepository = commitRepository;
        this.credentialService = credentialService;
    }

    /**
     * The two revisions to compare for one document.
     *
     * @param filesBefore the sources as they were
     * @param filesAfter  the sources as they are now
     * @param beforeRef   the revision the "before" side was read at
     * @param afterRef    the revision the "after" side was read at
     * @param skipped     why the document was skipped, or {@code null}
     */
    public record Comparison(List<AiDtos.SourceFile> filesBefore,
                              List<AiDtos.SourceFile> filesAfter,
                              String beforeRef,
                              String afterRef,
                              String skipped) {

        public boolean isSkipped() {
            return skipped != null;
        }
    }

    // -----------------------------------------------------------------
    // document-scoped entry point
    // -----------------------------------------------------------------

    /**
     * Resolve the comparison for one document, by id.
     *
     * <p>This is the entry point of the manual "scan this document"
     * flow, which knows nothing but the document id. The repository and
     * the pull request are looked up here.
     *
     * @return the comparison, or {@code null} when the document does
     *         not belong to the repository, or when there is nothing
     *         to compare
     */
    public Comparison resolveForDocument(UUID workspaceId,
                                         UUID repositoryId,
                                         UUID documentId,
                                         UUID pullRequestId) {
        Repository repo = repositoryRepository.findById(repositoryId).orElse(null);
        if (repo == null || !repo.getWorkspaceId().equals(workspaceId)) {
            return null;
        }
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !doc.getWorkspaceId().equals(workspaceId)) {
            return null;
        }
        PullRequest pr = pullRequestId == null
                ? null
                : pullRequestRepository.findById(pullRequestId).orElse(null);

        String token = resolveAccessToken(repo, null);
        if (token == null || token.isBlank()) {
            return skipped("No working GitHub connection is available for this repository — "
                    + "the access token has expired or was revoked. Reconnect and try again.");
        }
        return resolve(repo, token, doc, pr);
    }

    /**
     * Resolve the comparison for one document.
     *
     * @param repo   the repository the document belongs to
     * @param token  the GitHub credential to read the sources with
     * @param doc    the document to compare
     * @param pr     the pull request the scan is anchored to, or {@code null}
     */
    public Comparison resolve(Repository repo,
                              String token,
                              Document doc,
                              PullRequest pr) {
        if (pr != null) {
            return resolveForPullRequest(repo, token, doc, pr);
        }
        return resolveAgainstHead(repo, token, doc);
    }

    /**
     * The GitHub credential to read the repository with.
     *
     * <p>The credential of the user who connected the repository is
     * preferred — that is the one whose scopes cover the repository.
     * The requesting user and the other members of the owning workspace
     * are the fallbacks, and every candidate is validated against
     * GitHub before it is used, so one expired token does not fail the
     * whole scan.
     *
     * @param requestedBy the user who triggered the scan, or {@code null}
     */
    public String resolveAccessToken(Repository repo, UUID requestedBy) {
        return credentialService.resolveTokenForRepository(repo, requestedBy);
    }

    // -----------------------------------------------------------------
    // pull-request anchored
    // -----------------------------------------------------------------

    /**
     * Compare the pull request's base and head revisions.
     *
     * <p>The changed files of the pull request are the interesting
     * ones: they are what the drift detector has to look at. When the
     * document documents one of them, the set is narrowed down to the
     * files it actually covers — comparing it against files it says
     * nothing about produces noise, not findings.
     */
    private Comparison resolveForPullRequest(Repository repo,
                                             String token,
                                             Document doc,
                                             PullRequest pr) {
        List<GithubPullRequestFile> prFiles =
                githubClient.listPullRequestFiles(token, repo.getOwner(), repo.getName(),
                        pr.getGithubPrNumber());
        if (prFiles == null || prFiles.isEmpty()) {
            return skipped("The pull request touches no files.");
        }

        Set<String> changedPaths = new LinkedHashSet<>();
        for (GithubPullRequestFile f : prFiles) {
            if (f.path() != null && !f.path().isBlank()) {
                changedPaths.add(f.path());
            }
        }

        Set<String> covered = coveredPaths(doc, repo);
        Set<String> paths = covered.isEmpty()
                ? changedPaths
                : intersect(changedPaths, covered);
        if (paths.isEmpty()) {
            return skipped("The pull request does not touch the code this document covers.");
        }

        String beforeRef = pr.getBaseBranch() != null && !pr.getBaseBranch().isBlank()
                ? pr.getBaseBranch() : repo.getDefaultBranch();
        String afterRef = pr.getHeadBranch() != null && !pr.getHeadBranch().isBlank()
                ? pr.getHeadBranch() : beforeRef;

        List<AiDtos.SourceFile> before = download(repo, token, paths, beforeRef);
        List<AiDtos.SourceFile> after = download(repo, token, paths, afterRef);
        if (after.isEmpty()) {
            return skipped("The changed files could not be read from GitHub.");
        }
        return new Comparison(before, after, beforeRef, afterRef, null);
    }

    // -----------------------------------------------------------------
    // head-anchored (no pull request)
    // -----------------------------------------------------------------

    /**
     * Compare the code as it was when the document was written against
     * the code as it is now.
     *
     * <p>The "before" anchor is the commit SHA the document's own
     * version records — that is exactly the revision the documentation
     * describes.
     */
    private Comparison resolveAgainstHead(Repository repo,
                                          String token,
                                          Document doc) {
        Set<String> covered = coveredPaths(doc, repo);
        if (covered.isEmpty()) {
            return skipped("This document is not linked to any indexed code.");
        }

        String beforeRef = beforeAnchor(doc, repo);
        if (beforeRef == null) {
            return skipped("There is no earlier revision of the code to compare against.");
        }
        String afterRef = repo.getDefaultBranch();
        if (beforeRef.equals(afterRef)) {
            return skipped("The code has not changed since the document was written.");
        }

        // Only the files that actually differ can hold drift. Asking
        // GitHub for the diff first turns N file downloads into one
        // request plus the downloads of the changed files alone.
        Set<String> changed = changedPaths(repo, token, beforeRef, afterRef);
        if (changed.isEmpty()) {
            return skipped("The code has not changed since the document was written.");
        }

        Set<String> paths = intersect(changed, covered);
        if (paths.isEmpty()) {
            return skipped("None of the code this document covers has changed.");
        }

        List<AiDtos.SourceFile> before = download(repo, token, paths, beforeRef);
        List<AiDtos.SourceFile> after = download(repo, token, paths, afterRef);
        if (after.isEmpty()) {
            return skipped("The source files could not be read from GitHub.");
        }
        if (before.isEmpty()) {
            // Nothing to compare — every file of the document appeared
            // after the anchor commit. That is not drift, it is a
            // document that was written before its code existed.
            return skipped("The code of this document did not exist at the anchor commit.");
        }
        return new Comparison(before, after, beforeRef, afterRef, null);
    }

    /**
     * The files that differ between two revisions.
     *
     * <p>One compare request, not one request per file. This is what
     * makes a repository-wide scan finish in seconds instead of
     * minutes: a repository with a thousand files would otherwise cost
     * a thousand round trips per document.
     */
    private Set<String> changedPaths(Repository repo,
                                     String token,
                                     String base,
                                     String head) {
        List<GithubPullRequestFile> files = githubClient.compareCommits(
                token, repo.getOwner(), repo.getName(), base, head);
        Set<String> paths = new LinkedHashSet<>();
        for (GithubPullRequestFile f : files) {
            if (f.path() != null && !f.path().isBlank()) {
                paths.add(f.path());
            }
        }
        return paths;
    }

    /**
     * The revision the document's documentation describes.
     *
     * <p>The newest version that carries a commit SHA wins: it is the
     * closest revision the platform knows of, and therefore the
     * smallest window of change to examine.
     *
     * <p>When no version records one — a hand-written document, or one
     * written by a path that does not stamp the commit — the oldest
     * commit the platform has ingested for the repository is used
     * instead. Without that fallback every such document would be
     * skipped and the scan would report "nothing to compare" for a
     * repository that clearly has drift.
     */
    private String beforeAnchor(Document doc, Repository repo) {
        List<DocumentVersion> versions =
                versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(doc.getId());
        for (DocumentVersion v : versions) {
            if (v.getSourceCommitSha() != null && !v.getSourceCommitSha().isBlank()) {
                return v.getSourceCommitSha();
            }
        }
        // The document does not record the revision it was written
        // against, so the code as it stood when the document was last
        // touched is the closest the platform can offer.
        OffsetDateTime writtenAt = doc.getUpdatedAt() != null
                ? doc.getUpdatedAt()
                : doc.getCreatedAt();
        if (writtenAt != null) {
            Optional<Commit> atWriteTime = commitRepository
                    .findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                            repo.getId(), writtenAt);
            if (atWriteTime.isPresent()) {
                return atWriteTime.get().getSha();
            }
        }
        // Nothing predates the document — compare against the earliest
        // revision the platform knows of.
        return commitRepository.findFirstByRepositoryIdOrderByAuthoredAtAsc(repo.getId())
                .map(Commit::getSha)
                .orElse(null);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * The files a document talks about.
     *
     * <p>Prefer the files of the code entities the document is linked
     * to. A document without links is a repository-level overview, so
     * the files of every indexed entity of the repository are used
     * instead.
     */
    private Set<String> coveredPaths(Document doc, Repository repo) {
        Set<String> paths = new LinkedHashSet<>();

        List<CodeDocumentLink> links = linkRepository.findAllByDocumentId(doc.getId());
        if (!links.isEmpty()) {
            List<UUID> entityIds = links.stream()
                    .map(CodeDocumentLink::getCodeEntityId)
                    .toList();
            for (CodeEntity entity : codeEntityRepository.findAllById(entityIds)) {
                if (entity.getFilePath() != null && !entity.getFilePath().isBlank()) {
                    paths.add(entity.getFilePath());
                }
            }
        }

        if (paths.isEmpty() && doc.getRepositoryId() != null
                && doc.getRepositoryId().equals(repo.getId())) {
            for (CodeEntity entity
                    : codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repo.getId())) {
                if (entity.getFilePath() != null && !entity.getFilePath().isBlank()) {
                    paths.add(entity.getFilePath());
                }
            }
        }
        return paths;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : a) {
            if (b.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Read the given paths at a revision.
     *
     * <p>A file that does not exist at that revision yields no entry —
     * the file was added after it. The drift detector treats a file
     * that only exists on one side as a full add or remove, which is
     * exactly right.
     *
     * <p>Contents are cached for a short while. A repository-wide scan
     * compares every document against the same head revision, and
     * without the cache that head would be downloaded once per
     * document — the dominant cost of the whole scan.
     */
    private List<AiDtos.SourceFile> download(Repository repo,
                                              String token,
                                              Set<String> paths,
                                              String ref) {
        List<AiDtos.SourceFile> files = new ArrayList<>();
        int count = 0;
        for (String path : paths) {
            if (count >= MAX_FILES_PER_DOCUMENT) {
                log.info("Reached the {}-file cap for one drift comparison of {} — truncating",
                        MAX_FILES_PER_DOCUMENT, repo.getFullName());
                break;
            }
            try {
                String content = cachedContent(repo, token, path, ref);
                if (content == null || content.isBlank()) {
                    continue;
                }
                if (content.length() > MAX_FILE_CHARS) {
                    content = content.substring(0, MAX_FILE_CHARS) + "\n… (truncated)";
                }
                files.add(new AiDtos.SourceFile(path, content));
                count++;
            } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
                // An expired credential must not be mistaken for an
                // unchanged file: it is the difference between "there
                // is nothing to report" and "the scan could not run".
                throw e;
            } catch (Exception e) {
                log.debug("Could not fetch {} at {}: {}", path, ref, e.getMessage());
            }
        }
        return files;
    }

    /**
     * The content of one file at one revision, cached.
     *
     * <p>The cache is deliberately short-lived and bounded: it exists
     * to serve the many reads of a single scan, not to become a second
     * copy of the repository.
     */
    private String cachedContent(Repository repo, String token, String path, String ref) {
        String key = repo.getFullName() + " " + ref + " " + path;
        CachedFile cached = contentCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < CONTENT_CACHE_TTL_MS) {
            return cached.content();
        }
        String content = githubClient.getFileContent(
                token, repo.getOwner(), repo.getName(), path, ref);
        if (contentCache.size() > CONTENT_CACHE_MAX_ENTRIES) {
            contentCache.clear();
        }
        contentCache.put(key, new CachedFile(content, now));
        return content;
    }

    /** One cached file revision. */
    private record CachedFile(String content, long fetchedAt) {
    }

    private static Comparison skipped(String reason) {
        return new Comparison(List.of(), List.of(), null, null, reason);
    }
}
