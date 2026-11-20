package com.livingdocs.modules.document;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.document.service.DocumentFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that resolve a file inside a repository to the LivingDocs
 * document that describes it.
 *
 * <p>This mapping is the glue between the two halves of the platform:
 * the repository side knows files, the document side knows documents,
 * and without a reliable mapping a proposal raised for a file could
 * never be traced back to the document whose content it is about.
 *
 * <p>The cases below come from the real data: the repository names its
 * files {@code api-client.md} while the document slug is
 * {@code apiclient}, and the overview documents are named after their
 * doc type ({@code architecture.md} for {@code ARCHITECTURE}).
 */
class DocumentFileResolverTest {

    private DocumentRepository documentRepository;
    private DocumentFileResolver resolver;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        resolver = new DocumentFileResolver(documentRepository);
        repositoryId = UUID.randomUUID();
        when(documentRepository.findFirstByRepositoryIdAndFilePath(any(), anyString()))
                .thenReturn(Optional.empty());
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of());
    }

    private static Document document(String slug, String docType, String title, String filePath) {
        Document d = new Document(null, null, null, title, slug, docType, null, false, null);
        d.setFilePath(filePath);
        return d;
    }

    @Test
    void resolvesBySlugWhenTheFileStemMatches() {
        Document doc = document("apiclient", "AUTO_GENERATED", "ApiClient", null);
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(doc));

        Optional<Document> resolved = resolver.resolve(repositoryId, "docs/api-client.md");

        assertTrue(resolved.isPresent());
        assertEquals("apiclient", resolved.get().getSlug());
    }

    @Test
    void resolvesByDocTypeWhenTheFileStemNamesTheType() {
        Document doc = document("repo-livingdocs-architecture", "ARCHITECTURE",
                "LivingDocs — Architecture", null);
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(doc));

        Optional<Document> resolved = resolver.resolve(repositoryId, "docs/architecture.md");

        assertTrue(resolved.isPresent());
        assertEquals("ARCHITECTURE", resolved.get().getDocType());
    }

    @Test
    void resolvesByTitleForHandWrittenDocuments() {
        Document doc = document("po", "CUSTOM", "PO", null);
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(doc));

        assertTrue(resolver.resolve(repositoryId, "docs/po.md").isPresent());
    }

    @Test
    void anExplicitBindingWinsOverEveryHeuristic() {
        Document doc = document("apiclient", "AUTO_GENERATED", "ApiClient", "docs/custom/path.md");
        when(documentRepository.findFirstByRepositoryIdAndFilePath(repositoryId, "docs/custom/path.md"))
                .thenReturn(Optional.of(doc));

        Optional<Document> resolved = resolver.resolve(repositoryId, "docs/custom/path.md");

        assertTrue(resolved.isPresent());
        assertEquals("docs/custom/path.md", resolved.get().getFilePath());
        // The scan must not run when the binding is explicit.
        verify(documentRepository, never())
                .findAllByRepositoryIdOrderByUpdatedAtDesc(any());
    }

    @Test
    void anAmbiguousMatchResolvesToNothingRatherThanToTheWrongDocument() {
        // Two documents whose titles normalise to the same name. Picking
        // either of them would attach a proposal to the wrong document.
        Document first = document("one", "CUSTOM", "PO", null);
        Document second = document("two", "CUSTOM", "P.O", null);
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(first, second));

        assertTrue(resolver.resolve(repositoryId, "docs/po.md").isEmpty());
    }

    @Test
    void aFileNobodyDescribesStaysARepositoryLevelProposal() {
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(document("apiclient", "AUTO_GENERATED", "ApiClient", null)));

        assertTrue(resolver.resolve(repositoryId, "docs/unrelated.md").isEmpty());
    }

    @Test
    void theGuessedPathFromTheMigrationIsNotTreatedAsABinding() {
        // The first version of migration V58 derived the path from the
        // slug. Such a path must not shadow the real match, otherwise
        // the commit dialog would write a second file next to the
        // real one.
        Document doc = document("apiclient", "AUTO_GENERATED", "ApiClient", "docs/apiclient.md");
        when(documentRepository.findFirstByRepositoryIdAndFilePath(repositoryId, "docs/apiclient.md"))
                .thenReturn(Optional.of(doc));
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(doc));

        Optional<Document> resolved = resolver.resolve(repositoryId, "docs/api-client.md");

        assertTrue(resolved.isPresent());
        // The guessed path was replaced by the path that was found.
        assertEquals("docs/api-client.md", resolved.get().getFilePath());
    }

    @Test
    void theResolvedPathIsRememberedOnTheDocument() {
        Document doc = document("apiclient", "AUTO_GENERATED", "ApiClient", null);
        when(documentRepository.findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(doc));

        resolver.resolve(repositoryId, "docs/api-client.md");

        verify(documentRepository).save(doc);
        assertEquals("docs/api-client.md", doc.getFilePath());
    }
}
