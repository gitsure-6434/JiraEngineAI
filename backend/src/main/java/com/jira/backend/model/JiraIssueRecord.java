package com.jira.backend.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class JiraIssueRecord {

    private final String ticketId;
    private final String title;
    private final String description;
    private final String status;
    private final String resolution;
    @lombok.Builder.Default
    private final String comments = "";

    public String toIndexableText() {
        return """
                Ticket: %s
                Title: %s
                Status: %s
                Resolution: %s
                Description: %s
                Comments: %s
                """.formatted(
                ticketId,
                nullToEmpty(title),
                nullToEmpty(status),
                nullToEmpty(resolution),
                nullToEmpty(description),
                nullToEmpty(comments));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
