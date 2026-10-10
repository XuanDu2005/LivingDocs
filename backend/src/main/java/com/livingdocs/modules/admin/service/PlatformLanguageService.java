package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.PlatformLanguageResponse;
import com.livingdocs.modules.admin.dto.UpdatePlatformLanguageRequest;
import com.livingdocs.modules.admin.model.PlatformLanguage;
import com.livingdocs.modules.admin.repository.PlatformLanguageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages the platform-wide list of supported programming languages.
 *
 * <p>Changes here are the defaults that new workspaces inherit. Existing
 * workspaces are not affected — they keep their per-workspace
 * {@code workspace_languages} overrides.
 */
@Service
public class PlatformLanguageService {

    private final PlatformLanguageRepository repository;

    public PlatformLanguageService(PlatformLanguageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PlatformLanguageResponse> listLanguages() {
        return repository.findAllByOrderBySortOrderAscLanguageCodeAsc().stream()
                .map(PlatformLanguageResponse::from)
                .toList();
    }

    @Transactional
    public PlatformLanguageResponse updateLanguage(String languageCode,
                                                    UpdatePlatformLanguageRequest req) {
        PlatformLanguage p = repository.findByLanguageCode(languageCode)
                .orElseThrow(() -> new NotFoundException("Language not found: " + languageCode));
        p.setLanguageName(req.languageName());
        p.setEnabled(req.enabled());
        p.setDefaultPrompt(req.defaultPrompt());
        p.setSortOrder(req.sortOrder());
        return PlatformLanguageResponse.from(repository.save(p));
    }

    /**
     * Helper used by the workspace creation flow to seed a freshly
     * created workspace with the platform's default language list.
     */
    @Transactional
    public List<PlatformLanguage> snapshotActiveLanguages() {
        return repository.findAllByOrderBySortOrderAscLanguageCodeAsc().stream()
                .filter(PlatformLanguage::isEnabled)
                .toList();
    }
}
