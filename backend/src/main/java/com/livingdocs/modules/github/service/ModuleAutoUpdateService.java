package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.github.dto.RepositoryModuleResponse;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.model.RepositoryModule;
import com.livingdocs.modules.github.repository.RepositoryModuleRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
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

/**
 * Manages the per-module auto-update toggle.
 *
 * <p>This is the middle level of the auto-update hierarchy:
 * <ol>
 *   <li>the workspace policy ({@code auto_update_on_pr} / {@code
 *       auto_update_on_commit});</li>
 *   <li>the repository toggle ({@link Repository#isAutoUpdateEnabled()});</li>
 *   <li>the <em>module</em> toggle — this service;</li>
 *   <li>the document toggle ({@code Document.autoUpdateEnabled}).</li>
 * </ol>
 *
 * <p>A module is a path prefix inside the repository. When a file
 * changes, the auto-update service asks this service whether every
 * module covering that file is enabled; a module with auto-update off
 * vetoes the regeneration of the documents it covers.
 */
@Service
public class ModuleAutoUpdateService {

    private static final Logger log = LoggerFactory.getLogger(ModuleAutoUpdateService.class);

    private final RepositoryModuleRepository moduleRepository;
    private final RepositoryRepository repositoryRepository;
    private final CodeEntityRepository codeEntityRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final PermissionService permissionService;

    public ModuleAutoUpdateService(RepositoryModuleRepository moduleRepository,
                                   RepositoryRepository repositoryRepository,
                                   CodeEntityRepository codeEntityRepository,
                                   WorkspaceMemberRepository memberRepository,
                                   PermissionService permissionService) {
        this.moduleRepository = moduleRepository;
        this.repositoryRepository = repositoryRepository;
        this.codeEntityRepository = codeEntityRepository;
        this.memberRepository = memberRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<RepositoryModuleResponse> listForRepository(UUID workspaceId, UUID repositoryId) {
        requireMembership(workspaceId);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        return moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId).stream()
                .map(RepositoryModuleResponse::from)
                .toList();
    }

    /**
     * The module directories detected in a repository.
     *
     * <p>Derived from the indexed code entities: the distinct directory
     * prefixes of the files that contain them. This gives the author a
     * ready-made list of modules to toggle instead of making them type
     * paths by hand.
     */
    @Transactional(readOnly = true)
    public List<RepositoryModuleResponse> detectModules(UUID workspaceId, UUID repositoryId) {
        requireMembership(workspaceId);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        Set<String> directories = new LinkedHashSet<>();
        for (CodeEntity entity : codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId)) {
            String dir = directoryOf(entity.getFilePath());
            if (dir != null) {
                directories.add(dir);
            }
        }
        // Existing rows win: a module the user already configured keeps
        // its stored toggle even if the detection would re-create it.
        var existing = moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId);
        var byPath = new java.util.HashMap<String, RepositoryModule>();
        existing.forEach(m -> byPath.put(m.getModulePath(), m));

        List<RepositoryModuleResponse> out = new ArrayList<>();
        for (String dir : directories) {
            RepositoryModule module = byPath.get(dir);
            if (module == null) {
                out.add(new RepositoryModuleResponse(
                        null, repositoryId.toString(), workspaceId.toString(),
                        dir, dir, true, null, null));
            } else {
                out.add(RepositoryModuleResponse.from(module));
            }
        }
        // Rows that no longer correspond to a detected directory are
        // still listed: the user may have configured them by hand.
        existing.stream()
                .filter(m -> !directories.contains(m.getModulePath()))
                .forEach(m -> out.add(RepositoryModuleResponse.from(m)));
        return out;
    }

    @Transactional
    public RepositoryModuleResponse create(UUID workspaceId, UUID repositoryId,
                                            String modulePath, String displayName,
                                            boolean autoUpdateEnabled) {
        requireMembership(workspaceId);
        permissionService.requireAnyPermission(CurrentUser.requireId(),
                PermissionCodes.MODULE_AUTO_UPDATE_MANAGE);
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Repository not found in this workspace");
        }
        String normalized = RepositoryModule.normalizePath(modulePath);
        Optional<RepositoryModule> existing =
                moduleRepository.findByRepositoryIdAndModulePath(repositoryId, normalized);
        RepositoryModule module;
        if (existing.isPresent()) {
            module = existing.get();
            module.setAutoUpdateEnabled(autoUpdateEnabled);
            if (displayName != null && !displayName.isBlank()) {
                module.setDisplayName(displayName);
            }
        } else {
            module = new RepositoryModule(repositoryId, workspaceId, normalized,
                    displayName == null || displayName.isBlank() ? normalized : displayName,
                    autoUpdateEnabled);
        }
        RepositoryModule saved = moduleRepository.save(module);
        log.info("Module {} autoUpdateEnabled = {} (repo {})",
                saved.getModulePath(), saved.isAutoUpdateEnabled(), repo.getFullName());
        return RepositoryModuleResponse.from(saved);
    }

    @Transactional
    public RepositoryModuleResponse setAutoUpdate(UUID workspaceId, UUID moduleId, boolean enabled) {
        requireMembership(workspaceId);
        permissionService.requireAnyPermission(CurrentUser.requireId(),
                PermissionCodes.MODULE_AUTO_UPDATE_MANAGE);
        RepositoryModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new NotFoundException("Module not found"));
        if (!module.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Module not found in this workspace");
        }
        module.setAutoUpdateEnabled(enabled);
        RepositoryModule saved = moduleRepository.save(module);
        log.info("Module {} autoUpdateEnabled = {}", saved.getModulePath(), enabled);
        return RepositoryModuleResponse.from(saved);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID moduleId) {
        requireMembership(workspaceId);
        permissionService.requireAnyPermission(CurrentUser.requireId(),
                PermissionCodes.MODULE_AUTO_UPDATE_MANAGE);
        RepositoryModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new NotFoundException("Module not found"));
        if (!module.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Module not found in this workspace");
        }
        moduleRepository.delete(module);
    }

    /**
     * Is auto-update enabled for every module that covers this file?
     *
     * <p>Called by the auto-update pipeline for each changed file. A
     * single module with auto-update off vetoes the file, because the
     * document it feeds is one of that module's documents.
     *
     * <p>When the repository has no module configuration at all, the
     * answer is {@code true} — the module layer is opt-in and must not
     * silently disable the repository-level behaviour.
     */
    @Transactional(readOnly = true)
    public boolean isAutoUpdateAllowed(UUID repositoryId, String filePath) {
        List<RepositoryModule> modules =
                moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId);
        if (modules.isEmpty()) {
            return true;
        }
        for (RepositoryModule module : modules) {
            if (!module.isAutoUpdateEnabled() && module.covers(filePath)) {
                log.debug("Module {} has auto-update disabled — vetoing {}",
                        module.getModulePath(), filePath);
                return false;
            }
        }
        return true;
    }

    /**
     * The modules covering a set of changed files, filtered down to the
     * ones that veto the update.
     */
    @Transactional(readOnly = true)
    public Set<String> vetoedFiles(UUID repositoryId, Set<String> changedFilePaths) {
        Set<String> vetoed = new LinkedHashSet<>();
        if (changedFilePaths == null || changedFilePaths.isEmpty()) {
            return vetoed;
        }
        List<RepositoryModule> modules =
                moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId);
        for (RepositoryModule module : modules) {
            if (module.isAutoUpdateEnabled()) {
                continue;
            }
            for (String path : changedFilePaths) {
                if (module.covers(path)) {
                    vetoed.add(path);
                }
            }
        }
        return vetoed;
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private void requireMembership(UUID workspaceId) {
        UUID userId = CurrentUser.requireId();
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isEmpty()) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
    }

    /**
     * The directory part of a file path, or {@code null} for a file that
     * sits at the repository root.
     */
    static String directoryOf(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        String p = filePath.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return null;
        }
        String dir = p.substring(0, idx);
        return RepositoryModule.normalizePath(dir);
    }

    /** Guard against a caller passing an empty module path. */
    static void requirePath(String modulePath) {
        if (modulePath == null || modulePath.isBlank()) {
            throw new BadRequestException("modulePath is required");
        }
    }
}
