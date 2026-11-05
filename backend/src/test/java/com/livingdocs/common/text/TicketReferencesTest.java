package com.livingdocs.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketReferencesTest {

    @Test
    void findsATicketInACommitMessage() {
        assertEquals("PROJ-123",
                TicketReferences.first("PROJ-123: fix the drift detector"));
    }

    @Test
    void findsATicketInAPullRequestTitle() {
        assertEquals("LIV-42",
                TicketReferences.first("LIV-42 Add template compliance check"));
    }

    @Test
    void returnsTheFirstTicketWhenThereAreSeveral() {
        assertEquals("PROJ-1",
                TicketReferences.first("PROJ-1 and PROJ-2 and PROJ-3"));
    }

    @Test
    void returnsNullWhenThereIsNoTicket() {
        assertNull(TicketReferences.first("just a commit message"));
        assertNull(TicketReferences.first(""));
        assertNull(TicketReferences.first(null));
    }

    /**
     * A lowercase word must not be read as a ticket — otherwise every
     * sentence containing something like "e-mail" would produce a
     * reference.
     */
    @Test
    void ignoresLowercasePrefixes() {
        assertNull(TicketReferences.first("the e-mail arrived"));
    }

    /**
     * The number must not run into a word: {@code PROJ-123abc} is not
     * the ticket {@code PROJ-123}.
     */
    @Test
    void doesNotTruncateATrailingWord() {
        assertNull(TicketReferences.first("PROJ-123abc"));
    }

    /**
     * A pure number after the dash is the Jira shape, so it matches.
     */
    @Test
    void acceptsThePlainJiraShape() {
        assertEquals("ABC-1", TicketReferences.first("ABC-1"));
    }

    @Test
    void collectsEveryDistinctTicket() {
        var all = TicketReferences.all("PROJ-1, PROJ-2, PROJ-1");

        assertEquals(2, all.size());
        assertTrue(all.contains("PROJ-1"));
        assertTrue(all.contains("PROJ-2"));
    }

    @Test
    void collectsNothingFromEmptyText() {
        assertTrue(TicketReferences.all(null).isEmpty());
        assertTrue(TicketReferences.all("").isEmpty());
    }
}
