package com.livingdocs.modules.cicd.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the generated GitHub Actions workflow.
 *
 * <p>The workflow's only job is to ask LivingDocs for the verdict and
 * honour the answer. These tests pin that contract down, because the
 * bug they guard against is subtle: a workflow that decides on its own
 * makes the workspace's merge policy decorative, and nothing fails
 * loudly when the policy changes.
 */
class GitHubActionsWorkflowGeneratorTest {

    private static final String BACKEND = "https://livingdocs.example.com";
    private static final String WORKSPACE = "ws-1111";
    private static final String REPOSITORY = "repo-2222";

    private static String yaml() {
        return GitHubActionsWorkflowGenerator.generate(BACKEND, WORKSPACE, REPOSITORY);
    }

    @Test
    void workflowCallsTheMergeGateEndpoint() {
        String yaml = yaml();

        assertTrue(yaml.contains("/api/v1/cicd/workspaces/$LIVINGDOCS_WORKSPACE_ID"
                        + "/repositories/$LIVINGDOCS_REPOSITORY_ID/merge-gate"),
                "the workflow must ask LivingDocs for the merge verdict:\n" + yaml);
    }

    @Test
    void workflowPresentsTheCiToken() {
        String yaml = yaml();

        assertTrue(yaml.contains("X-LivingDocs-CI-Token: $LIVINGDOCS_CI_TOKEN"),
                "the workflow must authenticate with the workspace CI token:\n" + yaml);
        assertTrue(yaml.contains("secrets.LIVINGDOCS_CI_TOKEN"),
                "the token must come from a repository secret:\n" + yaml);
    }

    @Test
    void workflowPassesThePullRequestNumber() {
        String yaml = yaml();

        assertTrue(yaml.contains("pullRequestNumber=${{ github.event.pull_request.number }}"),
                "the gate must be evaluated for the pull request being built:\n" + yaml);
    }

    @Test
    void workflowFailsOnlyWhenLivingDocsSaysBlocked() {
        String yaml = yaml();

        assertTrue(yaml.contains(".blocked // false"),
                "the workflow must read the blocked flag from the response:\n" + yaml);
        assertTrue(yaml.contains("if [ \"$BLOCKED\" = \"true\" ]; then"),
                "the job must fail when the verdict says blocked:\n" + yaml);
        assertTrue(yaml.contains("exit 1"),
                "the failing step must exit non-zero so the check fails:\n" + yaml);
    }

    @Test
    void workflowDoesNotDecideOnItsOwn() {
        String yaml = yaml();

        assertFalse(yaml.contains("blockOnCritical"),
                "the workflow must not carry its own block decision:\n" + yaml);
        assertFalse(yaml.contains("driftSeverityThreshold"),
                "the severity threshold is a workspace setting, not a workflow setting:\n" + yaml);
    }

    @Test
    void workflowTriggersOnPullRequests() {
        String yaml = yaml();

        assertTrue(yaml.contains("pull_request:"),
                "the workflow must run on pull requests:\n" + yaml);
        assertTrue(yaml.contains("types: [opened, synchronize, reopened]"),
                "the workflow must re-run when the branch is updated:\n" + yaml);
    }

    @Test
    void workflowEmbedsTheBackendUrlAndIds() {
        String yaml = yaml();

        assertTrue(yaml.contains("LIVINGDOCS_BACKEND_URL: " + BACKEND));
        assertTrue(yaml.contains("LIVINGDOCS_WORKSPACE_ID: " + WORKSPACE));
        assertTrue(yaml.contains("LIVINGDOCS_REPOSITORY_ID: " + REPOSITORY));
    }
}
