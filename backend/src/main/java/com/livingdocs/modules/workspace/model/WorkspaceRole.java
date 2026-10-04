package com.livingdocs.modules.workspace.model;

/**
 * Roles a member can hold inside a workspace.
 *
 * <p>Managers can administer members and all documents. Members can read
 * and contribute but cannot manage the workspace or other members.
 */
public enum WorkspaceRole {
    MANAGER,
    MEMBER
}