package com.livingdocs.modules.document.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.dto.DocumentTagResponse;
import com.livingdocs.modules.document.model.DocumentTag;
import com.livingdocs.modules.document.repository.DocumentTagRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentTagService {

    private final DocumentTagRepository tagRepository;
    private final JdbcTemplate jdbcTemplate;

    public DocumentTagService(DocumentTagRepository tagRepository, JdbcTemplate jdbcTemplate) {
        this.tagRepository = tagRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<DocumentTagResponse> listTags(UUID workspaceId) {
        return tagRepository.findByWorkspaceIdOrderByNameAsc(workspaceId)
                .stream().map(DocumentTagResponse::from).toList();
    }

    @Transactional
    public DocumentTagResponse createTag(UUID workspaceId, String name, String colorHex) {
        if (tagRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceId, name)) {
            throw new ConflictException("Tag này đã tồn tại trong Workspace!");
        }
        DocumentTag tag = tagRepository.save(new DocumentTag(workspaceId, name, colorHex));
        return DocumentTagResponse.from(tag);
    }

    @Transactional
    public void assignTagToDocument(UUID documentId, UUID tagId) {
        // Dùng JdbcTemplate để không cần đụng vào class Document (tránh conflict)
        String sql = "INSERT INTO document_tag_assignments (document_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
        jdbcTemplate.update(sql, documentId, tagId);
    }

    @Transactional
    public void removeTagFromDocument(UUID documentId, UUID tagId) {
        String sql = "DELETE FROM document_tag_assignments WHERE document_id = ? AND tag_id = ?";
        jdbcTemplate.update(sql, documentId, tagId);
    }
    
    /* Retrieve the list of tags for a document. */
    @Transactional(readOnly = true)
    public List<DocumentTagResponse> getTagsForDocument(UUID documentId) {
        String sql = "SELECT t.id, t.name, t.color_hex FROM document_tags t " +
                     "JOIN document_tag_assignments a ON t.id = a.tag_id " +
                     "WHERE a.document_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentTagResponse(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("color_hex")
        ), documentId);
    }
}