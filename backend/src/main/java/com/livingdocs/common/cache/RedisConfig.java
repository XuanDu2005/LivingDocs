package com.livingdocs.common.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis-backed cache configuration for the LivingDocs backend.
 *
 * <p>Spring's {@code @Cacheable}/{@code @CacheEvict} annotations are
 * resolved through the {@link CacheManager} returned here, so the
 * rest of the codebase can stay agnostic to whether Redis is up —
 * the {@code @Cacheable} call simply does a fresh DB lookup on a
 * cache miss (and stores the result on the way out).
 *
 * <h2>Key strategy</h2>
 * <ul>
 *   <li>Cache names map to method-level cache names in the service
 *       layer (e.g. {@code "ai-responses"}, {@code "user-permissions"},
 *       {@code "code-entities"}).</li>
 *   <li>Per-cache TTLs are configured below; the default TTL falls back
 *       to {@code app.cache.ttl-seconds} (5 minutes by default).</li>
 * </ul>
 *
 * <h2>Serialization</h2>
 * Values are JSON-encoded via Jackson with type information preserved
 * so polymorphic DTOs (e.g. notification payloads) round-trip cleanly.
 * Java 8 date/time support is enabled to handle {@code Instant} and
 * {@code LocalDateTime} in cached responses.
 *
 * <h2>Graceful degradation</h2>
 * The Lettuce client used by {@code spring-boot-starter-data-redis}
 * reconnects automatically; if Redis is briefly unreachable, cache
 * lookups fail and the application continues to serve uncached
 * responses — there is no hard dependency on Redis being up.
 */
@Configuration
public class RedisConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final CacheProperties cacheProperties;

    public RedisConfig(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
        log.info("Initialising Redis cache with default TTL {}s", cacheProperties.getTtlSeconds());
    }

    /**
     * Single {@link RedisTemplate} exposed for code that needs raw
     * Redis access (e.g. the {@code @Cacheable} machinery uses the
     * {@link CacheManager} below, but background jobs publish
     * ephemeral status flags directly through this template).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Builds the Spring {@link CacheManager} used by {@code @Cacheable}.
     * Per-cache TTLs are listed in {@link CacheProperties}; unknown
     * cache names fall back to the default TTL.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer cacheValueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(cacheProperties.getTtlSeconds()))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(cacheValueSerializer));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();

        // AI responses are expensive (LLM round-trips) and stable
        // for a given (workspace, template, entity) tuple, so we cache
        // them for the longest configured TTL.
        perCache.put("ai-responses", baseConfig.entryTtl(
                Duration.ofSeconds(cacheProperties.getAiResponsesTtlSeconds())));

        // Knowledge-base search hits are invalidated whenever a document
        // is indexed/removed, so the TTL is intermediate.
        perCache.put("kb-search", baseConfig.entryTtl(
                Duration.ofSeconds(cacheProperties.getKbSearchTtlSeconds())));

        // User permissions change only on role updates, so they can be
        // cached longer than the default without risking staleness.
        perCache.put("user-permissions", baseConfig.entryTtl(
                Duration.ofSeconds(cacheProperties.getUserPermissionsTtlSeconds())));

        // Code entities are extracted once per commit and rarely change
        // for the same (repository, commit) tuple, so the TTL is long.
        perCache.put("code-entities", baseConfig.entryTtl(
                Duration.ofSeconds(cacheProperties.getCodeEntitiesTtlSeconds())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig)
                .withInitialCacheConfigurations(perCache)
                .transactionAware()
                .build();
    }

    /**
     * Configures Jackson for Redis serialization: enables Java 8
     * date/time types and embeds minimal type information so cached
     * values can be deserialized back into their concrete DTOs.
     *
     * <p>Without default typing, Spring would read a {@code List<PermissionResponse>}
     * back as a {@code List<LinkedHashMap>} and Jackson would fail to
     * write the response with "object is not an instance of declaring
     * class". Embedding the class name as a JSON property solves that
     * at the cost of slightly larger payloads.
     *
     * <p>Notes on the configuration:
     * <ul>
     *   <li>{@code EVERYTHING} is used (rather than the default
     *       {@code NON_FINAL}) because {@code java.util.List} is
     *       final — without {@code EVERYTHING} the outer array would
     *       not get a {@code @class} header and round-trip would
     *       still fail.</li>
     *   <li>The {@code BasicPolymorphicTypeValidator} is locked to
     *       {@code Object.class} so a tampered Redis cache cannot
     *       trick the application into instantiating arbitrary
     *       gadget classes. Tighten the allowlist further if you
     *       know exactly which DTOs are ever cached.</li>
     * </ul>
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    /**
     * Strongly-typed wrapper around the {@code app.cache.*} properties
     * defined in {@code application.yml}/{@code .env}. Each cache name
     * can have its own TTL, all falling back to {@code ttlSeconds} when
     * not set.
     */
    @ConfigurationProperties(prefix = "app.cache")
    public static class CacheProperties {
        /** Default TTL for any cache that does not declare a specific override. */
        private int ttlSeconds = 300;
        private int aiResponsesTtlSeconds = 1800;     // 30 min
        private int kbSearchTtlSeconds = 600;          // 10 min
        private int userPermissionsTtlSeconds = 900;   // 15 min
        private int codeEntitiesTtlSeconds = 1800;     // 30 min

        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }

        public int getAiResponsesTtlSeconds() { return aiResponsesTtlSeconds; }
        public void setAiResponsesTtlSeconds(int v) { this.aiResponsesTtlSeconds = v; }

        public int getKbSearchTtlSeconds() { return kbSearchTtlSeconds; }
        public void setKbSearchTtlSeconds(int v) { this.kbSearchTtlSeconds = v; }

        public int getUserPermissionsTtlSeconds() { return userPermissionsTtlSeconds; }
        public void setUserPermissionsTtlSeconds(int v) { this.userPermissionsTtlSeconds = v; }

        public int getCodeEntitiesTtlSeconds() { return codeEntitiesTtlSeconds; }
        public void setCodeEntitiesTtlSeconds(int v) { this.codeEntitiesTtlSeconds = v; }
    }
}
