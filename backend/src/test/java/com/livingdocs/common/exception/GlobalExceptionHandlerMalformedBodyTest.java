package com.livingdocs.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a malformed request body is answered.
 *
 * <p>A body that Jackson cannot read — a string where an object was
 * expected, a truncated document, a number where a UUID was expected
 * — is a client mistake. It used to fall through to the catch-all and
 * answer 500, which told the caller their server had broken when in
 * fact they had sent nonsense, and wrote a full stack trace into the
 * log for what is a routine client error.
 */
class GlobalExceptionHandlerMalformedBodyTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("PUT", "/api/v1/workspaces/w1/documents/d1");
    }

    @Test
    void aMalformedBodyIsAnsweredWithBadRequest() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: unexpected character");

        ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().status());
    }

    @Test
    void theAnswerSaysTheBodyIsTheProblem() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: unexpected character");

        ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, request);

        assertEquals("Request body is malformed or unreadable", response.getBody().message());
    }

    @Test
    void theAnswerNamesThePathThatWasCalled() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: unexpected character");

        ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, request);

        assertEquals("/api/v1/workspaces/w1/documents/d1", response.getBody().path());
    }
}
