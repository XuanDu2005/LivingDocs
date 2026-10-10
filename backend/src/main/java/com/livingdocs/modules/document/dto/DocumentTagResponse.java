package com.livingdocs.modules.document.dto;

import com.livingdocs.modules.document.model.DocumentTag;
import java.util.UUID;

public record DocumentTagResponse(UUID id, String name, String colorHex) {
    public static DocumentTagResponse from(DocumentTag tag) {
        return new DocumentTagResponse(tag.getId(), tag.getName(), tag.getColorHex());
    }
}
