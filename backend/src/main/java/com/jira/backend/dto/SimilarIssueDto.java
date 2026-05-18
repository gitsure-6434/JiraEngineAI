package com.jira.backend.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class SimilarIssueDto {

    private final String ticketId;
    private final String title;
    private final String description;
    @lombok.Builder.Default
    private final String comments = "";
    private final String status;
    private final String resolution;
    private final double similarityScore;
}
