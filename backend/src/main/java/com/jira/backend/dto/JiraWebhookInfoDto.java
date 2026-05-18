package com.jira.backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class JiraWebhookInfoDto {

    private final String webhookUrl;
    private final String method;
    private final String syncProjectKey;
    private final List<String> supportedEvents;
    private final String jqlFilterHint;
    private final String secretHeader;
}
