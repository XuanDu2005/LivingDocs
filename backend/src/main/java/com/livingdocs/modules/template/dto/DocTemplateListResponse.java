package com.livingdocs.modules.template.dto;

import java.util.List;

/**
 * Wraps a list of templates in a workspace + global library view.
 */
public record DocTemplateListResponse(
        List<DocTemplateResponse> workspaceTemplates,
        List<DocTemplateResponse> globalTemplates
) {}