package com.livingdocs.modules.version.dto;

import com.livingdocs.modules.version.model.ActorRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The filter of the workspace-level change log.
 *
 * <p>Every field is optional: a {@code null} field means "do not
 * restrict on this axis". The filter is what turns the raw version
 * table into the answer to "what changed in this workspace, and who
 * changed it" — the requirement asks for the trail to be readable by
 * role (AI, Staff, Manager), by document, by repository and by date
 * range.
 *
 * <p>The sort and direction are kept as strings here because they come
 * straight from a query parameter; {@link #sortProperty()} and
 * {@link #ascending()} translate them into something the persistence
 * layer understands, and an unknown value falls back to the default
 * rather than failing the request.
 */
public record ChangeLogFilter(
        UUID documentId,
        UUID repositoryId,
        /** {@code AI}, {@code STAFF}, {@code DEVELOPER}, {@code MANAGER} or {@code SYSTEM}. */
        String actorRole,
        OffsetDateTime from,
        OffsetDateTime to,
        /** {@code CREATED_AT}, {@code ACTOR_ROLE} or {@code VERSION_NUMBER}. */
        String sort,
        /** {@code ASC} or {@code DESC}. */
        String direction) {

    /** The default ordering: newest change first. */
    public static final String SORT_CREATED_AT = "CREATED_AT";
    public static final String SORT_ACTOR_ROLE = "ACTOR_ROLE";
    public static final String SORT_VERSION_NUMBER = "VERSION_NUMBER";

    public static final String DIRECTION_ASC = "ASC";
    public static final String DIRECTION_DESC = "DESC";

    /**
     * The entity property the log is ordered by.
     *
     * <p>Unknown values fall back to {@code createdAt}: a typo in a
     * query parameter must not turn into a 500.
     */
    public String sortProperty() {
        String value = sort == null ? "" : sort.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case SORT_ACTOR_ROLE -> "actorRole";
            case SORT_VERSION_NUMBER -> "versionNumber";
            default -> "createdAt";
        };
    }

    /** Whether the ordering is ascending. Defaults to descending. */
    public boolean ascending() {
        String value = direction == null ? "" : direction.trim().toUpperCase(java.util.Locale.ROOT);
        return DIRECTION_ASC.equals(value);
    }

    /**
     * The requested actor role as an enum, or {@code null} when no
     * role filter was requested.
     *
     * <p>An unknown role is treated as "no filter" rather than as an
     * error: the filter panel offers a fixed set of roles, so an
     * unexpected value can only come from a hand-written request and
     * must not break the page.
     */
    public ActorRole actorRoleEnum() {
        if (actorRole == null || actorRole.isBlank()) {
            return null;
        }
        try {
            return ActorRole.valueOf(actorRole.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
