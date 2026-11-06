package com.livingdocs.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Evicts the resolved-permission cache when the application starts.
 *
 * <h2>Why this is needed</h2>
 * The effective permissions of a user are cached under
 * {@code user-permissions} for {@code app.cache.user-permissions-ttl-seconds}
 * (15 minutes by default). That cache is what makes the permission
 * check cheap — but it also means a change to the permission matrix
 * that is applied <em>outside</em> the application is invisible until
 * the cache expires.
 *
 * <p>A Flyway migration is exactly such a change. {@code V42} granted
 * {@code AUDIT_LOG_VIEW} to the {@code MANAGER} role; without an
 * eviction, every Manager who had already resolved their permissions
 * kept getting {@code 403 Forbidden} on the audit-trail endpoints for
 * up to a quarter of an hour after the migration ran. The grant was
 * correct in the database and invisible to the application — the worst
 * of both worlds, because the matrix UI shows the permission as
 * granted while the API refuses it.
 *
 * <p>Evicting on startup closes that window: the cache is repopulated
 * from the current matrix on the first request after the restart.
 *
 * <h2>Why the whole cache and not a single key</h2>
 * The cache holds three kinds of entries: the effective permission set
 * per user, the permission catalogue and the matrix per role. A
 * migration can change any of them, and there is no cheap way to know
 * which users are affected — a role change alters the effective set of
 * every holder of that role. Clearing the whole cache is therefore the
 * only correct option.
 *
 * <p>The cost is one round-trip to Redis on startup, and the cache is
 * rebuilt lazily by the requests that follow. When Redis is
 * unreachable the eviction is skipped: the application must still
 * start, and a stale entry then expires on its own.
 */
@Component
public class PermissionCacheEvictionRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(PermissionCacheEvictionRunner.class);

    private final CacheManager cacheManager;

    public PermissionCacheEvictionRunner(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        Cache cache = cacheManager.getCache(PermissionService.PERMISSIONS_CACHE);
        if (cache == null) {
            // The cache manager is configured without the
            // `user-permissions` cache — nothing to evict.
            return;
        }
        try {
            cache.clear();
            log.info("Evicted the '{}' cache so a migration that changed the "
                    + "permission matrix takes effect immediately",
                    PermissionService.PERMISSIONS_CACHE);
        } catch (Exception e) {
            // A cache backend that is down must not stop the
            // application from starting: the entries expire on their
            // own, and the application is fully functional without a
            // cache.
            log.warn("Could not evict the '{}' cache at startup: {}",
                    PermissionService.PERMISSIONS_CACHE, e.getMessage());
        }
    }
}
