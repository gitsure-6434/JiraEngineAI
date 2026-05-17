package com.jira.backend.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Getter
@Builder
@Jacksonized
public class AnalyzeResponseDto {

    private final List<SimilarIssueDto> similarIssues;
    private final String rootCauseSummary;
    private final String recommendedFix;
    private final String recommendedCodePatch;
    private final List<String> reproductionSteps;
    private final List<String> relatedTicketIds;
    private final boolean fromCache;
}
