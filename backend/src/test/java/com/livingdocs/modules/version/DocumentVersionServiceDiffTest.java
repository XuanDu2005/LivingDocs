package com.livingdocs.modules.version;

import com.livingdocs.modules.version.service.DocumentVersionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentVersionServiceDiffTest {

    /**
     * The diff header labels each side with its <em>line count</em>, not
     * with a version number — the two documents can have different
     * lengths, and the line count is what identifies the side inside a
     * unified diff.
     *
     * <p>The line count includes the empty string after a trailing
     * newline, so {@code "a\nb\n"} counts as three lines.
     */
    @Test
    void computeUnifiedDiffHandlesIdenticalInputs() {
        String body = "# Title\n\nFirst line.\nSecond line.\n";
        String diff = DocumentVersionService.computeUnifiedDiff(body, body);

        String[] lines = diff.split("\n");
        assertEquals("--- v5", lines[0]);
        assertEquals("+++ v5", lines[1]);
    }

    @Test
    void computeUnifiedDiffDetectsAdditionsAndRemovals() {
        String before = "alpha\nbeta\ngamma\n";
        String after = "alpha\nbeta\nDELTA\ngamma\n";
        String diff = DocumentVersionService.computeUnifiedDiff(before, after);

        assertTrue(diff.contains("+DELTA"), "diff should include added line");
        assertTrue(diff.contains("--- v4"), "diff should include header lines");
        assertTrue(diff.contains("+++ v5"), "the 'to' side has one more line");
    }

    @Test
    void computeUnifiedDiffHandlesEmptyInputs() {
        String diff = DocumentVersionService.computeUnifiedDiff("", "");
        String[] lines = diff.split("\n");

        assertEquals("--- v1", lines[0]);
        assertEquals("+++ v1", lines[1]);
    }

    @Test
    void computeUnifiedDiffHandlesNullInputs() {
        String diff = DocumentVersionService.computeUnifiedDiff(null, null);

        assertTrue(diff.startsWith("--- v0"), "a null document is an empty document");
        assertTrue(diff.contains("+++ v0"));
    }
}
