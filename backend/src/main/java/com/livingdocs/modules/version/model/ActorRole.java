package com.livingdocs.modules.version.model;

/**
 * The role that produced a {@link DocumentVersion}. Used by the UI to label
 * the change log and by the audit log to attribute every modification.
 */
public enum ActorRole {
    AI,
    STAFF,
    MANAGER,
    SYSTEM
}