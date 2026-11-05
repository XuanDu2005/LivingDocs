package com.livingdocs.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownSectionsTest {

    @Test
    void parsesAtxHeadings() {
        var sections = MarkdownSections.parse("# Overview\n\ntext\n\n## API surface\n\nmore\n");

        assertEquals(2, sections.size());
        assertEquals("Overview", sections.get(0).heading());
        assertEquals("overview", sections.get(0).slug());
        assertEquals("#overview", sections.get(0).anchor());
        assertEquals("API surface", sections.get(1).heading());
        assertEquals("api-surface", sections.get(1).slug());
        assertEquals("#api-surface", sections.get(1).anchor());
    }

    @Test
    void skipsHeadingsInsideCodeFences() {
        var sections = MarkdownSections.parse(
                "# Real\n\n```bash\n# not a heading\n```\n\n## Also real\n");

        assertEquals(2, sections.size());
        assertEquals("Real", sections.get(0).heading());
        assertEquals("Also real", sections.get(1).heading());
    }

    @Test
    void stripsClosingHashes() {
        var sections = MarkdownSections.parse("## Overview ##\n\ntext\n");

        assertEquals(1, sections.size());
        assertEquals("Overview", sections.get(0).heading());
    }

    @Test
    void handlesEmptyInput() {
        assertTrue(MarkdownSections.parse(null).isEmpty());
        assertTrue(MarkdownSections.parse("").isEmpty());
        assertTrue(MarkdownSections.parse("no headings at all").isEmpty());
    }

    @Test
    void slugifyProducesStableSlugs() {
        assertEquals("api-surface", MarkdownSections.slugify("API Surface"));
        assertEquals("overview", MarkdownSections.slugify("Overview"));
        assertEquals("getting-started", MarkdownSections.slugify("Getting Started!"));
        assertEquals("", MarkdownSections.slugify("  "));
    }

    /**
     * Two headings with the same text must not collapse onto one
     * anchor — otherwise the second comment would deep-link to the
     * first section.
     */
    @Test
    void disambiguatesDuplicateHeadings() {
        var sections = MarkdownSections.parse("# Notes\n\na\n\n# Notes\n\nb\n");

        assertEquals(2, sections.size());
        assertEquals("notes", sections.get(0).slug());
        assertEquals("notes-1", sections.get(1).slug());
    }

    @Test
    void findsSectionById() {
        var sections = MarkdownSections.parse("# Overview\n\ntext\n");

        var found = MarkdownSections.find(sections, "overview", null);
        assertNotNull(found);
        assertEquals("Overview", found.heading());
    }

    @Test
    void findsSectionByHeadingWhenIdDoesNotMatch() {
        var sections = MarkdownSections.parse("# API surface\n\ntext\n");

        var found = MarkdownSections.find(sections, null, "API Surface");
        assertNotNull(found);
        assertEquals("api-surface", found.slug());
    }

    /**
     * The anchor form (with the leading hash) is what the UI sends, so
     * it has to resolve to the same section.
     */
    @Test
    void acceptsTheAnchorForm() {
        var sections = MarkdownSections.parse("# Overview\n\ntext\n");

        var found = MarkdownSections.find(sections, "#overview", null);
        assertNotNull(found);
        assertEquals("overview", found.slug());
    }

    @Test
    void returnsNullForUnknownSection() {
        var sections = MarkdownSections.parse("# Overview\n\ntext\n");

        assertNull(MarkdownSections.find(sections, "nope", null));
        assertNull(MarkdownSections.find(sections, null, "nope"));
        assertNull(MarkdownSections.find(java.util.List.of(), "overview", null));
    }
}
