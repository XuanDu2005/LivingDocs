package com.livingdocs.modules.admin.web;

import com.livingdocs.common.security.RequirePlatformRole;
import com.livingdocs.modules.admin.dto.PlatformLanguageResponse;
import com.livingdocs.modules.admin.dto.UpdatePlatformLanguageRequest;
import com.livingdocs.modules.admin.service.PlatformLanguageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator endpoints that maintain the platform-wide list of
 * supported programming languages.
 *
 * <p>Each language declared here becomes a default toggle on every
 * newly created workspace. Existing workspaces keep their
 * {@code workspace_languages} overrides unchanged.
 */
@RestController
@RequestMapping("/api/v1/admin/languages")
@RequirePlatformRole({"ADMIN"})
@Tag(name = "Admin Languages", description = "Platform-wide supported programming languages")
public class AdminLanguageController {

    private final PlatformLanguageService service;

    public AdminLanguageController(PlatformLanguageService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List all platform-default programming languages")
    public List<PlatformLanguageResponse> list() {
        return service.listLanguages();
    }

    @PutMapping("/{languageCode}")
    @Operation(summary = "Update a platform language (name, enabled flag, default prompt, sort order)")
    public PlatformLanguageResponse update(@PathVariable String languageCode,
                                            @Valid @RequestBody UpdatePlatformLanguageRequest req) {
        return service.updateLanguage(languageCode, req);
    }
}
