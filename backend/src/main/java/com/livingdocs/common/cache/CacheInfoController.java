package com.livingdocs.common.cache;

import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Diagnostics endpoint for the Redis cache layer.
 *
 * <p>Lives at {@code /api/v1/admin/cache-info} (platform-admin only)
 * and returns a snapshot of:
 * <ul>
 *   <li>The configured cache names and their backing TTLs.</li>
 *   <li>Live {@code KEYS} for each cache (Redis SCAN, capped at 50).</li>
 *   <li>A quick health check on the Redis connection.</li>
 * </ul>
 *
 * <p>This endpoint is intentionally read-only: it never flushes cache
 * or reveals any user content. Its primary purpose is to let admins
 * verify that {@code @Cacheable} is actually writing through to
 * Redis after a refactor.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class CacheInfoController {

    private final CacheManager cacheManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConfig.CacheProperties cacheProperties;

    public CacheInfoController(CacheManager cacheManager,
                               RedisTemplate<String, Object> redisTemplate,
                               RedisConfig.CacheProperties cacheProperties) {
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        this.cacheProperties = cacheProperties;
    }

    @GetMapping("/cache-info")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> cacheInfo() {
        Map<String, Object> out = new HashMap<>();
        out.put("cacheNames", cacheManager.getCacheNames());

        Map<String, Object> ttls = new HashMap<>();
        ttls.put("default", cacheProperties.getTtlSeconds());
        ttls.put("ai-responses", cacheProperties.getAiResponsesTtlSeconds());
        ttls.put("kb-search", cacheProperties.getKbSearchTtlSeconds());
        ttls.put("user-permissions", cacheProperties.getUserPermissionsTtlSeconds());
        ttls.put("code-entities", cacheProperties.getCodeEntitiesTtlSeconds());
        out.put("ttls", ttls);

        // Probe each cache for a few keys so an admin can see live
        // entries without flooding the response.
        Map<String, Object> liveKeys = new HashMap<>();
        for (String name : cacheManager.getCacheNames()) {
            String prefix = name + "::";
            Set<String> keys = redisTemplate.keys(prefix + "*");
            liveKeys.put(name, keys == null ? java.util.List.of()
                    : keys.stream().limit(50).toList());
        }
        out.put("liveKeys", liveKeys);

        try {
            // Simple ping to confirm the connection is alive.
            redisTemplate.getConnectionFactory().getConnection().ping();
            out.put("redisStatus", "UP");
        } catch (Exception e) {
            out.put("redisStatus", "DOWN: " + e.getMessage());
        }
        return out;
    }
}
