package com.livingdocs.modules.document.service;

import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a file inside a repository to the LivingDocs document that
 * describes it.
 *
 * <p>This is the bridge between the two halves of LivingDocs. The
 * repository side knows about files ({@code docs/api-client.md}); the
 * document side knows about documents (a document whose slug is
 * {@code apiclient}). Without a resolver, a proposal raised for a file
 * could never be traced back to the document whose content it is about.
 *
 * <p>The lookup runs in four strategies, strongest first:
 * <ol>
 *     <li><b>The explicit binding.</b> {@code documents.file_path} names
 *         the file the document is published to. When the user set it,
 *         it is authoritative — no heuristic may override it.</li>
 *     <li><b>The slug.</b> The file stem matches the document slug
 *         ({@code docs/api-client.md} → {@code apiclient}). This is how
 *         the auto-generated per-entity documents are named: the slug
 *         is derived from the entity's simple name, and the file is
 *         written with the same name in kebab-case.</li>
 *     <li><b>The doc type.</b> The file stem matches the document type
 *         ({@code docs/architecture.md} → {@code ARCHITECTURE}). This is
 *         how the repository-overview documents are named.</li>
 *     <li><b>The title.</b> The file stem matches the document title,
 *         which covers the documents a user created by hand.</li>
 * </ol>
 *
 * <p>A strategy that matches more than one document is <em>ambiguous</em>
 * and is discarded rather than resolved arbitrarily: picking one of two
 * candidates would attach a proposal to the wrong document, which is
 * worse than leaving it as a repository-level proposal. The next
 * strategy is tried instead, and if none resolves unambiguously the
 * file simply has no document.
 *
 * <p>Matching is done on a normalised form — lower-cased with every
 * non-alphanumeric character removed — so {@code api-client},
 * {@code api_client} and {@code ApiClient} all compare equal.
 */
@Service
public class DocumentFileResolver {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileResolver.class);

    private final DocumentRepository documentRepository;

    public DocumentFileResolver(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Find the document that publishes the given file.
     *
     * @param repositoryId the repository the file lives in
     * @param filePath     the path of the file inside the repository
     * @return the document, or empty when no document describes the file
     */
    public Optional<Document> resolve(UUID repositoryId, String filePath) {
        if (repositoryId == null || filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        // The explicit binding wins outright. When a user has told
        // LivingDocs which file a document publishes to, that statement
        // is the truth, and no heuristic may overrule it.
        //
        // A path that migration V58 guessed from the slug is treated as
        // no binding at all: it was derived, not chosen, and it is
        // wrong for most documents.
        Optional<Document> exact = documentRepository
                .findFirstByRepositoryIdAndFilePath(repositoryId, filePath)
                .filter(d -> !isGuessed(filePath, d));
        if (exact.isPresent()) {
            return exact;
        }

        List<Document> candidates = documentRepository
                .findAllByRepositoryIdOrderByUpdatedAtDesc(repositoryId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // The stem is normalised as well: the repository names a file
        // `api-client.md` while the slug is `apiclient`, so the two
        // only compare equal once the separators are removed.
        String stem = normalise(stemOf(filePath));
        if (stem.isEmpty()) {
            return Optional.empty();
        }

        Optional<Document> bySlug = unique(candidates, d -> stem.equals(normalise(d.getSlug())));
        if (bySlug.isPresent()) {
            return remember(bySlug.get(), repositoryId, filePath);
        }
        Optional<Document> byDocType = unique(candidates,
                d -> stem.equals(normalise(d.getDocType())));
        if (byDocType.isPresent()) {
            return remember(byDocType.get(), repositoryId, filePath);
        }
        Optional<Document> byTitle = unique(candidates, d -> stem.equals(normalise(d.getTitle())));
        if (byTitle.isPresent()) {
            return remember(byTitle.get(), repositoryId, filePath);
        }

        log.debug("No document in repository {} describes {}", repositoryId, filePath);
        return Optional.empty();
    }

    /**
     * Record the path the document was found at.
     *
     * <p>Once a file has been matched to a document, the match is
     * written back onto the document so the next lookup is a single
     * indexed query instead of a scan over the repository's documents.
     *
     * <p>A path the user set by hand is never overwritten — except for
     * the guessed paths written by the first version of migration V58
     * ({@code docs/{slug}.md}). Those were derived from the slug and
     * are wrong for most documents: the repository names its files
     * {@code api-client.md} while the slug is {@code apiclient}, and
     * the overview documents are named after their doc type. A guessed
     * path would make the commit dialog write a second file next to the
     * real one, so it is replaced by the path that was actually found.
     *
     * @return the document, with its path updated
     */
    private Optional<Document> remember(Document document, UUID repositoryId, String filePath) {
        String current = document.getFilePath();
        if (current != null && !current.isBlank() && !isGuessed(current, document)) {
            return Optional.of(document);
        }
        try {
            document.setFilePath(filePath);
            documentRepository.save(document);
            log.info("Bound document {} to {} in repository {}",
                    document.getId(), filePath, repositoryId);
        } catch (Exception e) {
            // The binding is an optimisation, not a requirement. The
            // resolution already succeeded, so a failure to persist it
            // must not break the caller.
            log.debug("Could not persist the file path of document {}: {}", document.getId(), e.getMessage());
        }
        return Optional.of(document);
    }

    /**
     * Whether the path is the one migration V58 guessed from the slug.
     *
     * <p>Only the exact shape the migration produced counts: a path
     * whose stem is the document's slug. A path a user typed that
     * happens to look similar is left alone.
     */
    private static boolean isGuessed(String path, Document document) {
        return ("docs/" + document.getSlug() + ".md").equals(path.trim());
    }

    /**
     * The name of the file without its directories and without the
     * extension: {@code docs/api/auth.md} → {@code auth}.
     */
    private static String stemOf(String filePath) {
        String path = filePath.trim().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    /**
     * Reduce a name to its comparable form: lower case, letters and
     * digits only.
     */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The candidate matching the predicate, but only when it is the
     * only one.
     *
     * <p>Ambiguity is resolved by giving up, not by picking.
     */
    private static Optional<Document> unique(List<Document> candidates,
                                              java.util.function.Predicate<Document> predicate) {
        Document found = null;
        for (Document d : candidates) {
            if (predicate.test(d)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = d;
            }
        }
        return Optional.ofNullable(found);
    }
}
