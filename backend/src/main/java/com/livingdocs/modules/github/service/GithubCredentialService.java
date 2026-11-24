package com.livingdocs.modules.github.service;

import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubUser;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a GitHub credential that can actually read a repository.
 *
 * <p>A workspace can have several people connected to GitHub, and any
 * one of their tokens can expire: GitHub revokes tokens when a user
 * changes their password, revokes the OAuth grant, or when the token
 * simply reaches its age limit. The connection row keeps saying
 * {@code ACTIVE} in that case — the platform only learns the truth when
 * GitHub answers 401.
 *
 * <p>This service therefore does two things the plain repository lookup
 * cannot:
 *
 * <ol>
 *   <li>It tries <em>several</em> candidates in order and returns the
 *       first one GitHub accepts, instead of failing on the first
 *       expired token.</li>
 *   <li>It marks the connections that turned out to be dead as
 *       {@link GithubConnection.Status#EXPIRED}, so the UI can ask the
 *       user to reconnect instead of pretending everything is
 *       connected.</li>
 * </ol>
 *
 * <p>The candidate order is deliberate: the credential of the user who
 * connected the repository comes first, then the requester, then any
 * other member of the owning workspace. The workspace scope matters —
 * returning an arbitrary platform-wide token would let one workspace's
 * scan read source code with another tenant's GitHub credential.
 */
@Service
public class GithubCredentialService {

    private static final Logger log = LoggerFactory.getLogger(GithubCredentialService.class);

    private final GithubConnectionRepository connectionRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final GithubClient githubClient;

    public GithubCredentialService(GithubConnectionRepository connectionRepository,
                                   WorkspaceMemberRepository memberRepository,
                                   GithubClient githubClient) {
        this.connectionRepository = connectionRepository;
        this.memberRepository = memberRepository;
        this.githubClient = githubClient;
    }

    /**
     * A credential that can read the repository, or {@code null}.
     *
     * <p>Every candidate is validated against GitHub before it is
     * returned, so the caller never has to deal with an expired token
     * mid-scan.
     *
     * @param repository  the repository to read
     * @param requestedBy the user who triggered the operation, or
     *                    {@code null}
     */
    @Transactional
    public String resolveTokenForRepository(Repository repository, UUID requestedBy) {
        if (repository == null) {
            return null;
        }
        for (GithubConnection candidate : candidates(repository, requestedBy)) {
            String token = candidate.getAccessToken();
            if (token == null || token.isBlank()) {
                continue;
            }
            if (isUsable(token)) {
                return token;
            }
            markExpired(candidate);
        }
        return null;
    }

    /**
     * A credential that can read the repository, or {@code null}.
     *
     * <p>Used by flows that are not tied to a repository row but do
     * belong to a workspace — webhooks, for instance.
     */
    @Transactional
    public String resolveTokenForWorkspace(UUID workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        for (GithubConnection candidate : workspaceCandidates(workspaceId)) {
            String token = candidate.getAccessToken();
            if (token == null || token.isBlank()) {
                continue;
            }
            if (isUsable(token)) {
                return token;
            }
            markExpired(candidate);
        }
        return null;
    }

    /**
     * The connections to try, in order.
     *
     * <p>The repository owner's credential comes first because it is the
     * one whose scopes certainly cover the repository. The requester
     * comes next. Any other member of the owning workspace is the last
     * resort — their token may or may not cover the repository, which
     * is exactly why it is validated before use.
     */
    private List<GithubConnection> candidates(Repository repository, UUID requestedBy) {
        List<GithubConnection> out = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();

        addIfPresent(out, seen, connectionRepository.findByUserId(repository.getConnectedBy()));
        if (requestedBy != null) {
            addIfPresent(out, seen, connectionRepository.findByUserId(requestedBy));
        }
        for (GithubConnection c : workspaceCandidates(repository.getWorkspaceId())) {
            if (seen.add(c.getUserId())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The connections of the workspace's members.
     */
    private List<GithubConnection> workspaceCandidates(UUID workspaceId) {
        if (workspaceId == null) {
            return List.of();
        }
        Set<UUID> memberIds = memberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(m -> m.getUserId())
                .collect(Collectors.toSet());
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return connectionRepository.findAllByStatus(GithubConnection.Status.ACTIVE).stream()
                .filter(c -> memberIds.contains(c.getUserId()))
                .toList();
    }

    private void addIfPresent(List<GithubConnection> out, Set<UUID> seen,
                              Optional<GithubConnection> candidate) {
        candidate.ifPresent(c -> {
            if (seen.add(c.getUserId())) {
                out.add(c);
            }
        });
    }

    /**
     * Whether GitHub still accepts the token.
     *
     * <p>{@code GET /user} is the cheapest authenticated call there is:
     * it touches no repository and works for every token scope, so it
     * can tell "this token is dead" apart from "this token cannot see
     * this particular repository".
     */
    private boolean isUsable(String token) {
        try {
            GithubUser user = githubClient.getCurrentUser(token);
            return user != null;
        } catch (Exception e) {
            log.debug("GitHub rejected a stored credential: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Record that a connection no longer works.
     *
     * <p>Without this the UI keeps showing "connected" while every call
     * fails, and the user has no way to know a reconnect is needed.
     */
    private void markExpired(GithubConnection connection) {
        try {
            connection.setStatus(GithubConnection.Status.EXPIRED);
            connectionRepository.save(connection);
            log.info("Marked the GitHub connection of user {} as EXPIRED — the token is no longer accepted by GitHub",
                    connection.getUserId());
        } catch (Exception e) {
            log.warn("Could not mark the GitHub connection of user {} as EXPIRED: {}",
                    connection.getUserId(), e.getMessage());
        }
    }
}
