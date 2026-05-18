package com.jira.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JiraWebhookServiceTest {

    @Mock
    private JiraIssueSyncService jiraIssueSyncService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JiraProperties jiraProperties = new JiraProperties();
    private JiraWebhookService jiraWebhookService;

    @BeforeEach
    void setUp() {
        jiraProperties.setSyncProjectKey("SCRUM");
        jiraWebhookService = new JiraWebhookService(jiraProperties, jiraIssueSyncService);
    }

    @Test
    void acceptsScrumIssueUpdate() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "webhookEvent": "jira:issue_updated",
                  "issue": {
                    "key": "SCRUM-42",
                    "fields": {
                      "project": { "key": "SCRUM" }
                    }
                  }
                }
                """);

        var response = jiraWebhookService.handleWebhook(payload, null);

        assertThat(response.getStatus()).isEqualTo("accepted");
        assertThat(response.getIssueKey()).isEqualTo("SCRUM-42");
        verify(jiraIssueSyncService).syncByKeyAsync("SCRUM-42");
    }

    @Test
    void skipsNonScrumProject() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "webhookEvent": "jira:issue_created",
                  "issue": {
                    "key": "OTHER-1",
                    "fields": {
                      "project": { "key": "OTHER" }
                    }
                  }
                }
                """);

        var response = jiraWebhookService.handleWebhook(payload, null);

        assertThat(response.getStatus()).isEqualTo("skipped");
        verify(jiraIssueSyncService, never()).syncByKeyAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidSecretWhenConfigured() {
        jiraProperties.setWebhookSecret("expected-secret");

        assertThatThrownBy(() -> jiraWebhookService.handleWebhook(objectMapper.createObjectNode(), "wrong"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
