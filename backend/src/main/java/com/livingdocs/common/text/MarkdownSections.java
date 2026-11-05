package com.livingdocs.common.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the headings out of a markdown document.
 *
 * <p>LivingDocs documents are markdown, so the natural unit of "a part
 * of the document" is a markdown heading. Two features depend on
 * parsing them:
 * <ul>
 *   <li>the review workflow — a reviewer anchors a comment to a
 *       specific section (requirement #8);</li>
 *   <li>the template compliance check — a reviewer verifies that the
 *       document actually carries every section the template asks for
 *       (requirement #5).</li>
 * </ul>
 *
 * <p>Both need the same three things: the heading text, a stable slug
 * (so a comment can point at it) and a GitHub-style anchor (so the UI
 * can deep-link to it).
 */
public final class MarkdownSections {

    /** A markdown ATX heading: one to six {@code #} followed by the text. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private MarkdownSections() {
        // utility
    }

    /**
     * One heading of a markdown document.
     *
     * @param level    the heading level (1 for {@code #}, 2 for {@code ##}, …)
     * @param heading  the heading text, without the leading hashes
     * @param slug     the normalised slug, e.g. {@code overview}
     * @param anchor   the GitHub-style anchor, e.g. {@code #overview}
     * @param line     the zero-based line number the heading sits on
     */
    public record Section(int level, String heading, String slug, String anchor, int line) {
    }

    /**
     * Every heading of the document, in the order they appear.
     *
     * <p>Headings inside fenced code blocks are ignored — a shell script
     * that contains a {@code #} comment must not be mistaken for a
     * heading.
     */
    public static List<Section> parse(String markdown) {
        List<Section> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        String[] lines = markdown.split("\\R", -1);
        boolean inFence = false;
        Map<String, Integer> slugCounts = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher m = HEADING.matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            int level = m.group(1).length();
            String heading = m.group(2).strip();
            String base = slugify(heading);
            String slug = base;
            int seen = slugCounts.merge(base, 1, Integer::sum);
            if (seen > 1) {
                // GitHub-style disambiguation: the second heading with
                // the same text gets a numeric suffix.
                slug = base + "-" + (seen - 1);
            }
            out.add(new Section(level, heading, slug, "#" + slug, i));
        }
        return out;
    }

    /**
     * Normalise a heading into a slug: lowercase, non-alphanumeric
     * characters become hyphens, leading and trailing hyphens are
     * dropped.
     */
    public static String slugify(String heading) {
        if (heading == null || heading.isBlank()) {
            return "";
        }
        String slug = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{Alnum}]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug;
    }

    /**
     * Find the heading a review wants to comment on.
     *
     * <p>The lookup accepts either the slug or the raw heading text, so
     * the client does not have to know which form the UI produced. The
     * returned {@link Section} is {@code null} when the document has no
     * such heading — the caller then rejects the comment, because a
     * comment pointing at a section that does not exist would be
     * invisible in the document view.
     */
    public static Section find(List<Section> sections, String sectionId, String heading) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        if (sectionId != null && !sectionId.isBlank()) {
            String needle = sectionId.startsWith("#") ? sectionId.substring(1) : sectionId;
            for (Section s : sections) {
                if (s.slug().equalsIgnoreCase(needle)) {
                    return s;
                }
            }
        }
        if (heading != null && !heading.isBlank()) {
            String needle = heading.startsWith("#") ? heading.substring(1).strip() : heading.strip();
            for (Section s : sections) {
                if (s.heading().equalsIgnoreCase(needle)) {
                    return s;
                }
            }
        }
        return null;
    }
}
