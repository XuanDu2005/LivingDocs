package com.livingdocs.modules.template.model;

/**
 * The SCM event that made LivingDocs consider regenerating a document.
 *
 * <p>Every template carries three opt-in flags — one per event — and
 * this enum is what maps a webhook onto the flag it has to satisfy:
 *
 * <table>
 *   <caption>Trigger to template flag</caption>
 *   <tr><th>Trigger</th><th>Template flag</th></tr>
 *   <tr><td>{@link #COMMIT}</td><td>{@code auto_generate_on_commit}</td></tr>
 *   <tr><td>{@link #PR}</td><td>{@code auto_generate_on_pr}</td></tr>
 *   <tr><td>{@link #MERGE}</td><td>{@code auto_generate_on_merge}</td></tr>
 * </table>
 *
 * <p>The enum lives in the template module because the flags it guards
 * are template flags — the GitHub module only has to say <em>what</em>
 * happened, not <em>whether</em> it is allowed to regenerate anything.
 */
public enum AutoGenerateTrigger {

    /** A commit was pushed to a branch (the {@code push} webhook). */
    COMMIT,

    /** A pull request was opened or updated (the {@code pull_request} webhook). */
    PR,

    /** A pull request was merged (the {@code pull_request} webhook, {@code closed} + merged). */
    MERGE
}
