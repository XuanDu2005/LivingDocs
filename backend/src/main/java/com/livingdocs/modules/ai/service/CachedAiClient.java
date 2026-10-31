package com.livingdocs.modules.ai.service;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Thin, cache-friendly facade in front of {@link AiServiceClient}.
 *
 * <p>LLM calls are slow and expensive. Two callers with the same
 * inputs almost always want the same response, so we cache the
 * final result by a deterministic key derived from the request
 * payload. Cache misses still fall through to the underlying HTTP
 * client, so behaviour is unchanged when Redis is unavailable.
 *
 * <h2>Cache key</h2>
 * Built as {@code workspaceId + "|" + SHA-1(template, sortedSources)}.
 * We hash the source-file content so that two different document
 * versions with the same effective inputs collapse to one cache
 * entry — but minor whitespace / re-ordering changes do NOT, which
 * keeps the cache useful for real workloads.
 *
 * <p>The template <em>schema</em> is part of the key: two templates
 * with the same slug but different section/placeholder definitions
 * must not share a cache entry, otherwise the second generation
 * would silently reuse the first template's prose.
 *
 * <h2>Why not annotate {@code AiServiceClient} directly?</h2>
 * That client returns {@code null} on failure to signal "AI down",
 * which is different from "AI returned empty". Caching the null
 * case would freeze the platform in a degraded state during a real
 * outage. By caching only the success path here, callers continue
 * to receive fresh nulls on retry.
 */
@Service
public class CachedAiClient {

    private static final Logger log = LoggerFactory.getLogger(CachedAiClient.class);

    private final AiServiceClient delegate;

    public CachedAiClient(AiServiceClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Cache wrapper around {@link AiServiceClient#generate}. Only the
     * happy path is cached; a {@code null} response is propagated
     * untouched.
     */
    @Cacheable(
            cacheNames = "ai-responses",
            key = "T(java.util.Objects).hash(#workspaceId, #template, #templateSchema, #focus, #languageHint, #sourceFiles.![path], #sourceFiles.![content])",
            unless = "#result == null"
    )
    public AiDtos.GenerateResponse generateCached(UUID workspaceId,
                                                    List<AiDtos.SourceFile> sourceFiles,
                                                    String template,
                                                    String templateSchema,
                                                    String focus,
                                                    String languageHint) {
        log.debug("AI generate cache miss for workspace={}, files={}, template={}",
                workspaceId,
                sourceFiles == null ? 0 : sourceFiles.size(),
                template);
        return delegate.generate(workspaceId, sourceFiles, template, templateSchema, focus, languageHint);
    }

    /**
     * Convenience overload for callers that do not use a custom
     * template schema (e.g. the repository-wide "generate all" flow).
     *
     * <p>This overload carries its own {@code @Cacheable} annotation
     * rather than delegating to the six-argument method: Spring's
     * cache proxy is bypassed on internal {@code this} calls, so a
     * self-delegating method would silently never be cached.
     */
    @Cacheable(
            cacheNames = "ai-responses",
            key = "T(java.util.Objects).hash(#workspaceId, #template, #sourceFiles.![path], #sourceFiles.![content])",
            unless = "#result == null"
    )
    public AiDtos.GenerateResponse generateCached(UUID workspaceId,
                                                    List<AiDtos.SourceFile> sourceFiles,
                                                    String template) {
        log.debug("AI generate cache miss for workspace={}, files={}, template={}",
                workspaceId,
                sourceFiles == null ? 0 : sourceFiles.size(),
                template);
        return delegate.generate(workspaceId, sourceFiles, template, null, null, null);
    }

    /**
     * Cache wrapper around {@link AiServiceClient#detectDrift}. Only
     * non-null responses are cached so a transient outage doesn't
     * pin a "no drift" result.
     *
     * <p>The cache key MUST include the file <em>contents</em>, not just
     * their paths. Keying on paths alone means a commit that edits a
     * file without renaming it resolves to the previous run's verdict
     * — the drift result would silently lag behind the code. This is
     * the same key shape {@link #generateCached} uses.
     */
    @Cacheable(
            cacheNames = "ai-responses",
            key = "T(java.util.Objects).hash(#workspaceId, #docTitle, #currentBody, #filesBefore.![content], #filesAfter.![content])",
            unless = "#result == null"
    )
    public AiDtos.DriftResponse detectDriftCached(UUID workspaceId,
                                                    String docTitle,
                                                    String currentBody,
                                                    List<AiDtos.SourceFile> filesBefore,
                                                    List<AiDtos.SourceFile> filesAfter) {
        log.debug("AI drift cache miss for workspace={}, document={}",
                workspaceId, docTitle);
        return delegate.detectDrift(workspaceId, filesBefore, filesAfter, currentBody, docTitle);
    }
}