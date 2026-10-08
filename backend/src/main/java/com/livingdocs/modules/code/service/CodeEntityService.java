package com.livingdocs.modules.code.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.code.dto.CodeEntityResponse;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read access to extracted code entities.
 */
@Service
public class CodeEntityService {

    private final CodeEntityRepository repository;
    private final RepositoryRepository githubRepoRepository;
    private final WorkspaceService workspaceService;

    public CodeEntityService(CodeEntityRepository repository,
                             RepositoryRepository githubRepoRepository,
                             WorkspaceService workspaceService) {
        this.repository = repository;
        this.githubRepoRepository = githubRepoRepository;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public List<CodeEntityResponse> listForRepository(UUID actorId, UUID workspaceId, UUID repositoryId) {
        Repository repo = githubRepoRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());
        return repository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId).stream()
                .map(CodeEntityResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CodeEntityResponse> listCoverageGaps(UUID actorId, UUID workspaceId, UUID repositoryId) {
        Repository repo = githubRepoRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());
        
        // Gọi query tìm các Code Entity chưa có link tài liệu
        return repository.findCoverageGaps(repositoryId).stream()
                .map(CodeEntityResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CodeEntityResponse get(UUID actorId, UUID entityId) {
        CodeEntity e = repository.findById(entityId)
                .orElseThrow(() -> new NotFoundException("Code entity not found"));
        Repository repo = githubRepoRepository.findById(e.getRepositoryId())
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        workspaceService.requireMember(actorId, repo.getWorkspaceId());
        return CodeEntityResponse.from(e);
    }
}