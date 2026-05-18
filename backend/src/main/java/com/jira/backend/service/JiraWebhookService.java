package com.jira.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.dto.JiraWebhookResponseDto;
import com.jira.backend.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class JiraWebhookService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "jira:issue_created",
            "jira:issue_updated",
            "comment_created",
            "comment_updated");

    private final JiraProperties jiraProperties;
    private final JiraIssueSyncService jiraIssueSyncService;

    public JiraWebhookResponseDto handleWebhook(JsonNode payload, String providedSecret) {
        validateSecret(providedSecret);

        String event = textOrEmpty(payload, "webhookEvent");
        JsonNode issue = resolveIssueNode(payload);
        String issueKey = textOrEmpty(issue, "key");
        String projectKey = textOrEmpty(issue.path("fields").path("project"), "key");

        if (!StringUtils.hasText(event)) {
            return skipped(null, issueKey, "Missing webhookEvent");
        }

        if (!SUPPORTED_EVENTS.contains(event)) {
            return skipped(event, issueKey, "Event type not handled");
        }

        if (!StringUtils.hasText(issueKey)) {
            return skipped(event, null, "Missing issue key in payload");
        }

        if (!jiraProperties.getSyncProjectKey().equalsIgnoreCase(projectKey)) {
            return skipped(
                    event,
                    issueKey,
                    "Ignored: project '%s' is not '%s'".formatted(projectKey, jiraProperties.getSyncProjectKey()));
        }

        jiraIssueSyncService.syncByKeyAsync(issueKey);
        return JiraWebhookResponseDto.builder()
                .webhookEvent(event)
                .issueKey(issueKey)
                .status("accepted")
                .message("Issue queued for vector index sync (create or update)")
                .build();
    }

    private void validateSecret(String providedSecret) {
        String expected = jiraProperties.getWebhookSecret();
        if (!StringUtils.hasText(expected)) {
            return;
        }
        if (!expected.equals(providedSecret)) {
            throw new UnauthorizedException("Invalid webhook secret");
        }
    }

    private JsonNode resolveIssueNode(JsonNode payload) {
        JsonNode issue = payload.path("issue");
        if (!issue.isMissingNode() && !issue.isNull()) {
            return issue;
        }
        return payload.path("comment").path("issue");
    }

    private String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.path(field).asText("");
    }

    private JiraWebhookResponseDto skipped(String event, String issueKey, String message) {
        return JiraWebhookResponseDto.builder()
                .webhookEvent(event)
                .issueKey(issueKey)
                .status("skipped")
                .message(message)
                .build();
    }
}
