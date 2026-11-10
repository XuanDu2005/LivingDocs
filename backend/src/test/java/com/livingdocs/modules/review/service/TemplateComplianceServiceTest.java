package com.livingdocs.modules.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.template.service.DocTemplateService;
import com.livingdocs.modules.template.service.TemplateComplianceEvaluator;
import com.livingdocs.modules.template.service.TemplateSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The template compliance check compares a document body against the
 * sections of the template it was generated from.
 *
 * <p>These tests exercise the comparison itself — the part that decides
 * whether a reviewer sees a green or a red badge.
 */
class TemplateComplianceServiceTest {

    private TemplateComplianceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TemplateComplianceService(
                mock(com.livingdocs.modules.document.repository.DocumentRepository.class),
                mock(com.livingdocs.modules.version.repository.DocumentVersionRepository.class),
                mock(DocTemplateRepository.class),
                mock(DocTemplateService.class),
                new TemplateComplianceEvaluator(new TemplateSchema(objectMapper), objectMapper),
                mock(com.livingdocs.modules.workspace.service.WorkspaceService.class));
    }

    /**
     * A template with the given JSON body. The id is assigned by the
     * entity manager, so it stays {@code null} here — the compliance
     * check does not depend on it.
     */
    private DocTemplate templateWith(String bodyJson) {
        return new DocTemplate(
                null,                    // workspaceId — a global template
                "API reference",         // name
                "api-reference",         // slug
                null,                    // description
                "API",                   // docType
                1,                       // version
                bodyJson,                // body
                false,                   // isDefault
                UUID.randomUUID());      // createdBy
    }

    @Test
    void aDocumentThatFollowsTheTemplateIsCompliant() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "overview", "heading": "Overview" },
                    { "id": "api-surface", "heading": "API surface" }
                  ]
                }
                """);

        var result = service.evaluate(template, """
                # Overview

                Some text.

                # API surface

                More text.
                """);

        assertTrue(result.compliant(), "every template section is present");
        assertTrue(result.missingHeadings().isEmpty());
        assertTrue(result.outOfOrderHeadings().isEmpty());
        assertTrue(result.sections().get(0).present());
        assertFalse(result.sections().get(0).empty());
    }

    @Test
    void aMissingSectionMakesTheDocumentNonCompliant() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "overview", "heading": "Overview" },
                    { "id": "api-surface", "heading": "API surface" }
                  ]
                }
                """);

        var result = service.evaluate(template, "# Overview\n\nSome text.\n");

        assertFalse(result.compliant());
        assertTrue(result.missingHeadings().contains("API surface"));
    }

    /**
     * A heading that is there but says nothing is not the same as a
     * heading that is missing — the reviewer needs to know which of the
     * two it is.
     */
    @Test
    void anEmptySectionIsReportedAsEmpty() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "overview", "heading": "Overview" }
                  ]
                }
                """);

        var result = service.evaluate(template, "# Overview\n\n# Next\n\ntext\n");

        assertTrue(result.sections().get(0).present());
        assertTrue(result.sections().get(0).empty(),
                "the section has a heading but no content under it");
    }

    @Test
    void sectionsOutOfOrderAreReported() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "overview", "heading": "Overview" },
                    { "id": "api-surface", "heading": "API surface" }
                  ]
                }
                """);

        var result = service.evaluate(template, """
                # API surface

                text

                # Overview

                text
                """);

        assertFalse(result.compliant());
        assertTrue(result.outOfOrderHeadings().contains("API surface"),
                "the first template section appears second in the document");
        assertTrue(result.missingHeadings().isEmpty(),
                "the sections are all there — they are just in the wrong order");
    }

    /**
     * The template says "API surface" but the document says "API
     * Surface" — the reviewer is not going to penalise a capital
     * letter.
     */
    @Test
    void headingMatchingIsCaseInsensitive() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "api-surface", "heading": "API surface" }
                  ]
                }
                """);

        var result = service.evaluate(template, "# API Surface\n\ntext\n");

        assertTrue(result.compliant());
    }

    /**
     * A template whose body cannot be parsed must not fail every
     * document in the workspace — the check skips it instead.
     */
    @Test
    void anUnreadableTemplateBodyYieldsNoSections() {
        DocTemplate template = templateWith("this is not json");

        var result = service.evaluate(template, "# Overview\n\ntext\n");

        assertTrue(result.sections().isEmpty());
        assertTrue(result.compliant(),
                "a template that cannot be read cannot fail the document");
    }

    /**
     * A template without any sections at all is trivially satisfied —
     * there is nothing to check against.
     */
    @Test
    void aTemplateWithoutSectionsIsCompliant() {
        DocTemplate template = templateWith("{ \"sections\": [] }");

        var result = service.evaluate(template, "# Anything\n\ntext\n");

        assertTrue(result.compliant());
        assertTrue(result.sections().isEmpty());
    }

    /**
     * A template that says nothing about being strict is not strict —
     * the workspace has to opt in.
     */
    @Test
    void aTemplateIsNotStrictByDefault() {
        DocTemplate template = templateWith("""
                {
                  "sections": [
                    { "id": "overview", "heading": "Overview" }
                  ]
                }
                """);

        var result = service.evaluate(template, "# Something else\n\ntext\n");

        assertFalse(result.strict());
        assertTrue(result.missingRequiredHeadings().contains("Overview"),
                "the missing section is reported, whatever the strictness");
    }

    /**
     * A strict template that asks for a section the document does not
     * have — the section is reported as a missing <em>required</em>
     * one, which is what blocks a publication.
     */
    @Test
    void aStrictTemplateReportsTheMissingRequiredSections() {
        DocTemplate template = templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "notes", "heading": "Notes", "required": false }
                  ]
                }
                """);

        var result = service.evaluate(template, "# Overview\n\ntext\n");

        assertTrue(result.strict());
        assertFalse(result.compliant());
        assertTrue(result.missingHeadings().contains("Notes"));
        assertTrue(result.missingRequiredHeadings().isEmpty(),
                "the only missing section is the optional one");
    }

    /**
     * A strict template whose required section is missing — this is the
     * case that refuses a publication.
     */
    @Test
    void aStrictTemplateReportsAMissingRequiredSection() {
        DocTemplate template = templateWith("""
                {
                  "strict": true,
                  "sections": [
                    { "id": "overview", "heading": "Overview", "required": true },
                    { "id": "notes", "heading": "Notes", "required": false }
                  ]
                }
                """);

        var result = service.evaluate(template, "# Notes\n\ntext\n");

        assertTrue(result.strict());
        assertFalse(result.compliant());
        assertTrue(result.missingRequiredHeadings().contains("Overview"));
        assertFalse(result.missingRequiredHeadings().contains("Notes"),
                "an optional section never blocks a publication");
    }
}
