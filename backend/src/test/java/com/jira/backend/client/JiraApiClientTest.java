package com.jira.backend.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsIssueRefsFromIdOnlySearchResponse() throws Exception {
        String json =
                """
                {
                  "issues": [
                    { "id": "10001" },
                    { "id": "10000" }
                  ],
                  "isLast": true
                }
                """;

        var client = new JiraApiClient(null, null);
        Method method = JiraApiClient.class.getDeclaredMethod("extractIssueRefs", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) method.invoke(client, objectMapper.readTree(json));

        assertThat(refs).containsExactly("10001", "10000");
    }

    @Test
    void rejectsJiraErrorMessagesInSearchResponse() throws Exception {
        String json =
                """
                {
                  "errorMessages": ["Invalid JQL: project = UNKNOWN"],
                  "issues": []
                }
                """;

        var client = new JiraApiClient(null, null);
        Method method = JiraApiClient.class.getDeclaredMethod(
                "assertValidSearchResponse", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        var response = objectMapper.readTree(json);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.jira.backend.exception.JiraIntegrationException.class,
                () -> {
                    try {
                        method.invoke(client, response);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }
                        throw new RuntimeException(cause);
                    }
                });
    }

    @Test
    void mapsBulkFetchResponseWithFullFields() throws Exception {
        String json =
                """
                {
                  "issues": [
                    {
                      "id": "10001",
                      "key": "PROJ-1",
                      "fields": {
                        "summary": "Login bug",
                        "description": "401 after refresh",
                        "status": { "name": "Done" },
                        "resolution": { "name": "Fixed" }
                      }
                    }
                  ]
                }
                """;

        var client = new JiraApiClient(null, null);
        Method method = JiraApiClient.class.getDeclaredMethod("mapBulkFetchIssues", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var records = (List<com.jira.backend.model.JiraIssueRecord>) method.invoke(client, objectMapper.readTree(json));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getTicketId()).isEqualTo("PROJ-1");
        assertThat(records.get(0).getTitle()).isEqualTo("Login bug");
        assertThat(records.get(0).getStatus()).isEqualTo("Done");
    }
}
