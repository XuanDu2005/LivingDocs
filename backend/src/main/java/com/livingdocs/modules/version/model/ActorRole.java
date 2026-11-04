package com.livingdocs.modules.version.model;

/**
 * The role that produced a {@link DocumentVersion}. Used by the UI to label
 * the change log and by the audit log to attribute every modification.
 *
 * <p>The value is derived from the actor's platform role, so a
 * {@code DEVELOPER}'s edits are attributed to {@link #DEVELOPER} and not
 * to {@link #STAFF}. Before {@code DEVELOPER} existed, every human edit
 * was mislabelled as STAFF, which made the change log useless for
 * answering "who changed this?".
 */
public enum ActorRole {
    AI,
    STAFF,
    DEVELOPER,
    MANAGER,
    SYSTEM
}