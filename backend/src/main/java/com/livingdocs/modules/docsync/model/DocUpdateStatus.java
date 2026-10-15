package com.livingdocs.modules.docsync.model;

/**
 * Lifecycle of a {@link DocUpdateRequest}.
 *
 * <pre>
 *   PENDING_REVIEW  ──► APPROVED  ──► APPLIED
 *                  └─► REJECTED
 * </pre>
 *
 * <ul>
 *   <li>{@code PENDING_REVIEW}: auto-created by the push webhook; nobody
 *       has looked at it yet.</li>
 *   <li>{@code APPROVED}: a reviewer signed off; an apply step is now
 *       allowed to create a PR on GitHub.</li>
 *   <li>{@code REJECTED}: a reviewer vetoed the proposed change. The doc
 *       is left untouched.</li>
 *   <li>{@code APPLIED}: the GitHub PR was opened successfully. Terminal
 *       state.</li>
 * </ul>
 */
public enum DocUpdateStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    APPLIED;

    public boolean isTerminal() {
        return this == APPLIED || this == REJECTED;
    }
}
