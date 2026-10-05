package com.livingdocs.common.persistence;

import java.util.UUID;

/**
 * Centralised UUID generator. We use {@link UUID#randomUUID()} for new
 * rows so callers don't have to repeat the boilerplate; the v7 strategy
 * can be swapped in here when the underlying JVMs support it without
 * touching the rest of the codebase.
 */
public final class UuidGenerator {

    private UuidGenerator() {}

    public static UUID newId() {
        return UUID.randomUUID();
    }
}