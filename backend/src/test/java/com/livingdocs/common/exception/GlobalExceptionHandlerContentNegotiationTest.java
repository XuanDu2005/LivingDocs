package com.livingdocs.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a refusal is answered when the caller does not accept JSON.
 *
 * <p>The browser's {@code EventSource} opens the notification stream
 * with {@code Accept: text/event-stream}. A temporary-password account
 * reaching the stream is refused by the password-change interceptor,
 * and the refusal used to be rendered as a JSON body into a
 * {@code text/event-stream} response — which Spring could not do. It
 * answered 406, the exception handler itself failed, and the caller
 * saw a 500 for what was really a clean 403.
 *
 * <p>These tests pin the fix: the status code stays honest, and the
 * body degrades to plain text rather than failing.
 */
class GlobalExceptionHandlerContentNegotiationTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
    }

    @Test
    void aRefusalToAnEventStreamCallerKeepsTheForbiddenStatus() {
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<?> response = handler.handleForbidden(
                new ForbiddenException("This account must replace its temporary password."),
                request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void aRefusalToAnEventStreamCallerIsWrittenAsPlainText() {
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<?> response = handler.handleForbidden(
                new ForbiddenException("This account must replace its temporary password."),
                request);

        assertInstanceOf(String.class, response.getBody());
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
        assertTrue(response.getBody().toString().contains("must replace its temporary password"),
                "the reason has to reach the caller: " + response.getBody());
    }

    @Test
    void aRefusalToAJsonCallerKeepsTheStructuredBody() {
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);

        ResponseEntity<?> response = handler.handleForbidden(
                new ForbiddenException("This account must replace its temporary password."),
                request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertInstanceOf(ApiError.class, response.getBody());
        assertEquals(403, ((ApiError) response.getBody()).status());
    }

    @Test
    void aRefusalToACallerThatSaysNothingIsAnsweredWithJson() {
        // No Accept header at all: every API client means JSON.
        ResponseEntity<?> response = handler.handleForbidden(
                new ForbiddenException("This account must replace its temporary password."),
                request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertInstanceOf(ApiError.class, response.getBody());
    }

    @Test
    void aRefusalToAWildcardCallerIsAnsweredWithJson() {
        request.addHeader("Accept", "*/*");

        ResponseEntity<?> response = handler.handleForbidden(
                new ForbiddenException("This account must replace its temporary password."),
                request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertInstanceOf(ApiError.class, response.getBody());
    }

    @Test
    void anImpossibleRepresentationIsAnsweredWithNotAcceptable() {
        // The notification stream only produces text/event-stream, so a
        // caller that asks for JSON matches no handler at all.
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);

        ResponseEntity<?> response = handler.handleNotAcceptable(
                new HttpMediaTypeNotAcceptableException("No acceptable representation"),
                request);

        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.getStatusCode());
        assertInstanceOf(ApiError.class, response.getBody());
        assertEquals(406, ((ApiError) response.getBody()).status());
    }

    @Test
    void anImpossibleRepresentationIsNotAnsweredWithAServerError() {
        // Before the handler existed this fell through to the catch-all
        // and answered 500 for what is really a client mistake.
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);

        ResponseEntity<?> response = handler.handleNotAcceptable(
                new HttpMediaTypeNotAcceptableException("No acceptable representation"),
                request);

        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
}
