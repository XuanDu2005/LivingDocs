package com.livingdocs.modules.template;

import com.livingdocs.modules.template.model.OutputFormat;
import com.livingdocs.modules.template.service.TemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template renderer turns the markdown the AI produced into the
 * format the template asked for.
 *
 * <p>Two things matter here: that HTML is really produced (the setting
 * was dead metadata before), and that a format the renderer does not
 * implement never silently lies about what it produced.
 */
class TemplateRendererTest {

    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TemplateRenderer();
    }

    @Test
    void markdownIsReturnedUnchanged() {
        String markdown = "# Title\n\nSome text.\n";
        assertEquals(markdown, renderer.render(markdown, OutputFormat.MARKDOWN));
    }

    @Test
    void nullFormatIsTreatedAsMarkdown() {
        String markdown = "# Title\n";
        assertEquals(markdown, renderer.render(markdown, null));
    }

    @Test
    void markdownIsConvertedToHtml() {
        String html = renderer.render("# Title\n\nSome text.\n", OutputFormat.HTML);
        assertTrue(html.contains("<h1>Title</h1>"), html);
        assertTrue(html.contains("<p>Some text.</p>"), html);
        // The markdown must not survive as literal text.
        assertFalse(html.contains("# Title"), html);
    }

    @Test
    void gfmTablesAreRendered() {
        String html = renderer.render(
                "| a | b |\n|---|---|\n| 1 | 2 |\n", OutputFormat.HTML);
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<td>1</td>"), html);
    }

    @Test
    void rawHtmlInTheMarkdownIsEscaped() {
        // The document body is machine-generated content that ends up in
        // a browser and in a repository. An unescaped <script> in a code
        // sample would be an injection vector.
        String html = renderer.render("<script>alert(1)</script>\n", OutputFormat.HTML);
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void pdfFallsBackToMarkdownAndReportsMarkdownAsTheEffectiveFormat() {
        String markdown = "# Title\n";
        assertEquals(markdown, renderer.render(markdown, OutputFormat.PDF));
        assertEquals(OutputFormat.MARKDOWN, renderer.effectiveFormat(OutputFormat.PDF));
    }

    @Test
    void theEffectiveFormatOfASupportedFormatIsItself() {
        assertEquals(OutputFormat.MARKDOWN, renderer.effectiveFormat(OutputFormat.MARKDOWN));
        assertEquals(OutputFormat.HTML, renderer.effectiveFormat(OutputFormat.HTML));
    }

    @Test
    void pdfIsMarkedAsUnsupported() {
        assertTrue(OutputFormat.MARKDOWN.isSupported());
        assertTrue(OutputFormat.HTML.isSupported());
        assertFalse(OutputFormat.PDF.isSupported());
    }

    @Test
    void emptyInputRendersAsEmptyString() {
        assertEquals("", renderer.render(null, OutputFormat.HTML));
        assertEquals("", renderer.render("", OutputFormat.MARKDOWN));
    }
}
