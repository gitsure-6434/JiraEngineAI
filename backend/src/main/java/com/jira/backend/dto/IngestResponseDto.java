package com.jira.backend.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IngestResponseDto {

    private final int issuesFetched;
    private final int issuesIndexed;
    private final String message;
}
