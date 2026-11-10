package com.livingdocs.modules.template.service;

import com.livingdocs.modules.template.model.OutputFormat;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns the markdown a document is authored in into the format its
 * template asks for.
 *
 * <p>A template carries an {@link OutputFormat}. Until this class
 * existed the column was pure metadata: every document was markdown,
 * whatever the template said.
 *
 * <h2>What is supported</h2>
 * <ul>
 *   <li>{@link OutputFormat#MARKDOWN} — the markdown is returned
 *       unchanged. This is the canonical format of the platform: the
 *       review workflow anchors comments to markdown headings, the
 *       template compliance check counts markdown headings, and the
 *       diff view compares markdown.</li>
 *   <li>{@link OutputFormat#HTML} — the markdown is converted to HTML
 *       with commonmark-java.</li>
 *   <li>{@link OutputFormat#PDF} — <b>not implemented</b>. The value is
 *       still accepted by the API (the column and the enum exist), but
 *       the renderer falls back to markdown and logs a warning. The
 *       UI disables the option with a tooltip so nobody can pick it
 *       by accident.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * <p>The HTML is rendered with {@code escapeHtml(true)}, so raw HTML
 * embedded in the markdown is escaped rather than passed through. The
 * document body is machine-generated content that later reaches a
 * browser (and a repository), and an unescaped {@code <script>} in a
 * code sample would be an injection vector.
 *
 * <p>The service is stateless and thread-safe: the parser and the
 * renderer are immutable and shared.
 */
@Service
public class TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderer.class);

    private final Parser parser;
    private final HtmlRenderer htmlRenderer;

    public TemplateRenderer() {
        // GFM-ish dialect: the AI writes tables and struck-through text
        // regularly, and plain CommonMark would leave the table syntax
        // as literal text.
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.builder().build());
        this.parser = Parser.builder()
                .extensions(extensions)
                .build();
        this.htmlRenderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .build();
    }

    /**
     * Render a markdown body into the format the template asks for.
     *
     * @param markdown the markdown body; may be {@code null}
     * @param format   the target format; {@code null} is treated as
     *                 {@link OutputFormat#MARKDOWN}
     * @return the rendered body — never {@code null}
     */
    public String render(String markdown, OutputFormat format) {
        OutputFormat effective = format == null ? OutputFormat.MARKDOWN : format;
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return switch (effective) {
            case MARKDOWN -> markdown;
            case HTML -> toHtml(markdown);
            case PDF -> {
                // PDF is reserved for a future renderer. Falling back to
                // markdown keeps the document usable instead of
                // failing the generation; the warning tells the
                // operator why the format was not honoured.
                log.warn("Output format PDF is not supported yet — "
                        + "rendering this document as markdown instead");
                yield markdown;
            }
        };
    }

    /**
     * The format that will actually be produced for a requested format.
     *
     * <p>Useful for a caller that has to report what happened — the
     * requested PDF becomes MARKDOWN.
     */
    public OutputFormat effectiveFormat(OutputFormat format) {
        OutputFormat effective = format == null ? OutputFormat.MARKDOWN : format;
        return effective.isSupported() ? effective : OutputFormat.MARKDOWN;
    }

    private String toHtml(String markdown) {
        try {
            Node document = parser.parse(markdown);
            return htmlRenderer.render(document);
        } catch (RuntimeException e) {
            // A conversion failure must not lose the generated
            // document — fall back to the markdown, which is always
            // renderable.
            log.warn("Could not convert markdown to HTML, keeping the markdown: {}",
                    e.getMessage());
            return markdown;
        }
    }
}
