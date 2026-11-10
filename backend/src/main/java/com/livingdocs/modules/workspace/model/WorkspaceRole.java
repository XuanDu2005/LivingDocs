package com.livingdocs.modules.workspace.model;

/**
 * Roles a member can hold inside a workspace.
 *
 * <p>Managers can administer members and all documents. Members can read
 * and contribute but cannot manage the workspace or other members.
 *
 * <p>{@code DOCUMENTATION_OWNER} sits between the two: it owns the
 * <em>documentation governance</em> of the workspace — the templates, the
 * doc-type workflows and the governance settings — without being able to
 * administer the membership of the workspace. It exists because those two
 * responsibilities are genuinely different: a team may want one person to
 * own the shape of the documentation without handing them the keys to the
 * workspace itself.
 *
 * <p>The role is orthogonal to the platform-role axis. Holding it grants
 * nothing on its own: the capability axis still decides whether the
 * person may touch the governance knobs at all (see
 * {@code GOVERNANCE_MANAGE}), so the two axes are still combined with AND.
 */
public enum WorkspaceRole {
    MANAGER,
    DOCUMENTATION_OWNER,
    MEMBER
}
