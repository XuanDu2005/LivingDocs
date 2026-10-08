package com.livingdocs.modules.integrations.model;

/**
 * Lifecycle of an integration connection.
 *
 * <ul>
 *     <li>{@link Inst#ACTIVE}: token valid, ready to send/receive.</li>
 *     <li>{@link Inst#REVOKED}: disabled by an administrator. The row is
 *         kept (not deleted) so audit history and previously persisted
 *         events remain queryable.</li>
 *     <li>{@link Inst#ERROR}: last health-check failed. UI prompts the
 *         administrator to reconnect.</li>
 * </ul>
 */
public enum IntegrationStatus {
    ACTIVE,
    REVOKED,
    ERROR
}