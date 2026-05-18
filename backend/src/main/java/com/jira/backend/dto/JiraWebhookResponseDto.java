package com.jira.backend.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class JiraWebhookResponseDto {

    private final String webhookEvent;
    private final String issueKey;
    private final String status;
    private final String message;
}
