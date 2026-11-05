package com.livingdocs.common.text;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ticket references (Jira-style keys) from free text.
 *
 * <p>Requirement #2 of the STAFF role: the grounding panel should show
 * the ticket a change belongs to, next to the source file, the code
 * entity, the commit and the diff. Without a stored reference there was
 * nowhere to put it, so the reference is extracted from the text that
 * already describes the change — the commit message or the pull
 * request title.
 *
 * <p>A ticket key looks like {@code PROJ-123}: an upper-case project
 * prefix, a dash and a number. The pattern is deliberately conservative
 * so it does not match things like {@code RFC-2616} by accident: the
 * prefix must be two to ten letters, and the number must not be
 * followed by another letter or digit.
 */
public final class TicketReferences {

    /**
     * The project key followed by the issue number.
     *
     * <p>The negative lookahead on the trailing boundary keeps
     * {@code PROJ-1234a} from being read as {@code PROJ-123}.
     */
    private static final Pattern TICKET = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d+)(?![A-Za-z0-9])");

    private TicketReferences() {
        // utility
    }

    /**
     * The first ticket reference in the text, or {@code null} when there
     * is none.
     */
    public static String first(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = TICKET.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Every distinct ticket reference in the text, in the order they
     * appear.
     */
    public static Set<String> all(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = TICKET.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
