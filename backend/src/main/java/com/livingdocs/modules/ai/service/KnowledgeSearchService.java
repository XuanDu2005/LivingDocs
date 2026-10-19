package com.livingdocs.modules.ai.service;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cache-friendly wrapper around the AI service's knowledge-base
 * search endpoint.
 *
 * <p>Semantic search is one of the most expensive operations in
 * the platform (it embeds the query and computes cosine similarity
 * against every indexed chunk). The result is also highly stable
 * for the same query within a short window: the index only changes
 * when a document is indexed or removed.
 *
 * <h2>Cache invalidation</h2>
 * Entries live in the {@code kb-search} cache and expire after
 * {@code app.cache.kb-search-ttl-seconds} (default 10 min). For
 * stronger consistency callers can {@code @CacheEvict} on
 * index/remove events, but the TTL is short enough that
 * "stale-by-a-few-minutes" results are tolerable in practice.
 *
 * <p>Null responses (AI down) are propagated untouched so a real
 * outage isn't masked by a cached "no hits" result.
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    private final AiServiceClient aiClient;

    public KnowledgeSearchService(AiServiceClient aiClient) {
        this.aiClient = aiClient;
    }

    /**
     * Cache-aside wrapper for {@link AiServiceClient#search}.
     *
     * @param workspaceId workspace whose index should be searched
     * @param query       user-typed query string
     * @param topK        max number of hits to return; treated as
     *                    part of the cache key so different limits
     *                    don't collide
     */
    @Cacheable(
            cacheNames = "kb-search",
            key = "#workspaceId + ':' + #query + ':' + #topK",
            unless = "#result == null"
    )
    public AiDtos.SearchResponse search(UUID workspaceId, String query, int topK) {
        log.debug("Knowledge-base search cache miss for workspace={}, query='{}', topK={}",
                workspaceId, query, topK);
        return aiClient.search(query, workspaceId.toString(), topK);
    }
}
