package com.livingdocs.modules.review.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The filter and sort options of a review queue.
 *
 * <p>Requirement #9 of the STAFF role: "filter and sort the queue by
 * repository / template / drift severity / submission date".
 *
 * <p>Every field is optional — an unfiltered queue is the default.
 */
public record ReviewQueueFilter(
        UUID repositoryId,
        UUID templateId,
        String severity,
        String docType,
        OffsetDateTime submittedFrom,
        OffsetDateTime submittedTo,
        String sort,
        String direction
) {
    /** How the queue may be ordered. */
    public enum Sort {
        /** Newest submission first — the default. */
        SUBMITTED_AT,
        /** Lowest confidence first: the shakiest content surfaces first. */
        CONFIDENCE,
        /** Worst drift severity first. */
        SEVERITY
    }

    /** Which way to order. */
    public enum Direction {
        ASC,
        DESC
    }

    public ReviewQueueFilter {
        if (sort == null || sort.isBlank()) {
            sort = Sort.SUBMITTED_AT.name();
        }
        if (direction == null || direction.isBlank()) {
            direction = Direction.DESC.name();
        }
    }

    public Sort sortOption() {
        try {
            return Sort.valueOf(sort.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Sort.SUBMITTED_AT;
        }
    }

    public Direction directionOption() {
        try {
            return Direction.valueOf(direction.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Direction.DESC;
        }
    }

    public boolean isAscending() {
        return directionOption() == Direction.ASC;
    }

    /**
     * Whether a severity filter is set.
     */
    public boolean hasSeverity() {
        return severity != null && !severity.isBlank();
    }
}
