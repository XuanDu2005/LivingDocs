package com.livingdocs.modules.version;

import com.livingdocs.modules.version.service.DocumentVersionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentVersionServiceDiffTest {

    @Test
    void computeUnifiedDiffHandlesIdenticalInputs() {
        String body = "# Title\n\nFirst line.\nSecond line.\n";
        String diff = DocumentVersionService.computeUnifiedDiff(body, body);
        assertEquals("--- v3\n+++ v3", diff.split("\n")[0] + "\n" + diff.split("\n")[1]);
    }

    @Test
    void computeUnifiedDiffDetectsAdditionsAndRemovals() {
        String before = "alpha\nbeta\ngamma\n";
        String after = "alpha\nbeta\nDELTA\ngamma\n";
        String diff = DocumentVersionService.computeUnifiedDiff(before, after);
        assertTrue(diff.contains("+DELTA"), "diff should include added line");
        assertTrue(diff.contains("--- v3"), "diff should include header lines");
    }

    @Test
    void computeUnifiedDiffHandlesEmptyInputs() {
        assertEquals("--- v0\n+++ v0", DocumentVersionService.computeUnifiedDiff("", "").split("\n")[0]
                + "\n" + DocumentVersionService.computeUnifiedDiff("", "").split("\n")[1]);
    }
}