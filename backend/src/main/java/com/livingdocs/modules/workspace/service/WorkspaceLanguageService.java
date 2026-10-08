package com.livingdocs.modules.workspace.service;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceLanguageRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceLanguageResponse;
import com.livingdocs.modules.workspace.model.WorkspaceLanguage;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceLanguageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages per-workspace supported programming languages for AI.
 */
@Service
public class WorkspaceLanguageService {

    private final WorkspaceLanguageRepository repository;
    private final WorkspaceService workspaceService;

    public WorkspaceLanguageService(WorkspaceLanguageRepository repository,
                                    WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceLanguageResponse> listForWorkspace(UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MEMBER);
        return repository.findAllByWorkspaceIdOrderByLanguageNameAsc(workspaceId).stream()
                .map(WorkspaceLanguageResponse::from)
                .toList();
    }

    @Transactional
    public WorkspaceLanguageResponse updateLanguage(UUID workspaceId,
                                                      UpdateWorkspaceLanguageRequest req) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireRole(actor, workspaceId, WorkspaceRole.MANAGER);

        WorkspaceLanguage lang = repository
                .findByWorkspaceIdAndLanguageCode(workspaceId, req.languageCode())
                .orElseGet(() -> WorkspaceLanguage.of(
                        workspaceId, req.languageCode(), req.languageName(),
                        req.enabled(), req.customPrompt()));

        lang.applyChange(req.enabled(), req.customPrompt());
        return WorkspaceLanguageResponse.from(repository.save(lang));
    }
}
