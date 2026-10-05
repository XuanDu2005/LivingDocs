package com.livingdocs.modules.review.model;

/**
 * The verdict a reviewer or approver records on a document version.
 */
public enum ReviewDecision {
    APPROVED,
    REJECTED,
    REQUEST_CHANGES,
    COMMENT
}