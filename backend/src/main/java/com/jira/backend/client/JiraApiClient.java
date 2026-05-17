package com.jira.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.exception.BadRequestException;
import com.jira.backend.exception.JiraIntegrationException;
import com.jira.backend.model.JiraIssueRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class JiraApiClient implements JiraPort {

    private static final int PAGE_SIZE = 100;
    private static final int BULK_FETCH_SIZE = 100;
    private static final List<String> ISSUE_FIELDS =
            List.of("summary", "description", "status", "resolution");

    private final WebClient jiraWebClient;
    private final JiraProperties properties;

    public JiraApiClient(@Qualifier("jiraWebClient") WebClient jiraWebClient, JiraProperties properties) {
        this.jiraWebClient = jiraWebClient;
        this.properties = properties;
    }

    @Override
    public List<JiraIssueRecord> searchIssues(String jql, int maxResults) {
        validateConfiguration();

        try {
            List<String> issueRefs = collectIssueRefsFromSearch(jql, maxResults);
            if (issueRefs.isEmpty()) {
                return List.of();
            }
            return bulkFetchIssueDetails(issueRefs);
        } catch (WebClientResponseException ex) {
            throw new JiraIntegrationException(
                    "Jira search failed: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            if (ex instanceof JiraIntegrationException jiraIntegrationException) {
                throw jiraIntegrationException;
            }
            throw new JiraIntegrationException("Failed to call Jira API", ex);
        }
    }

    @Override
    public List<JiraIssueRecord> fetchIssuesByKeys(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return List.of();
        }

        String keys = ticketIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(String::toUpperCase)
                .distinct()
                .map(key -> "\"" + key + "\"")
                .collect(Collectors.joining(", "));

        if (!StringUtils.hasText(keys)) {
            return List.of();
        }

        String jql = "key in (" + keys + ")";
        return searchIssues(jql, ticketIds.size());
    }

    /**
     * Step 1: Enhanced JQL search often returns only internal issue ids (no fields).
     */
    private List<String> collectIssueRefsFromSearch(String jql, int maxResults) {
        List<String> issueRefs = new ArrayList<>();
        String nextPageToken = null;

        while (issueRefs.size() < maxResults) {
            int pageSize = Math.min(maxResults - issueRefs.size(), PAGE_SIZE);
            JsonNode response = executeJqlSearch(jql, pageSize, nextPageToken);
            issueRefs.addAll(extractIssueRefs(response));

            boolean isLast = !response.has("isLast") || response.path("isLast").asBoolean(true);
            nextPageToken = readNextPageToken(response);

            if (isLast || !StringUtils.hasText(nextPageToken)) {
                break;
            }
        }

        return issueRefs.size() > maxResults ? issueRefs.subList(0, maxResults) : issueRefs;
    }

    /**
     * Step 2: Load full issue payloads (summary, description, status, resolution).
     */
    private List<JiraIssueRecord> bulkFetchIssueDetails(List<String> issueRefs) {
        List<JiraIssueRecord> records = new ArrayList<>();

        for (int offset = 0; offset < issueRefs.size(); offset += BULK_FETCH_SIZE) {
            int end = Math.min(offset + BULK_FETCH_SIZE, issueRefs.size());
            List<String> batch = issueRefs.subList(offset, end);
            JsonNode response = executeBulkFetch(batch);
            records.addAll(mapBulkFetchIssues(response));
        }

        return records;
    }

    private JsonNode executeJqlSearch(String jql, int maxResults, String nextPageToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        if (StringUtils.hasText(nextPageToken)) {
            body.put("nextPageToken", nextPageToken);
        }

        return jiraWebClient.post()
                .uri("/rest/api/3/search/jql")
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .header(HttpHeaders.ACCEPT, "application/json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    private JsonNode executeBulkFetch(List<String> issueIdsOrKeys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issueIdsOrKeys", issueIdsOrKeys);
        body.put("fields", ISSUE_FIELDS);
        body.put("fieldsByKeys", false);

        return jiraWebClient.post()
                .uri("/rest/api/3/issue/bulkfetch")
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .header(HttpHeaders.ACCEPT, "application/json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    private List<String> extractIssueRefs(JsonNode response) {
        if (response == null || !response.has("issues")) {
            return List.of();
        }

        List<String> refs = new ArrayList<>();
        for (JsonNode issue : response.get("issues")) {
            if (issue.has("key") && StringUtils.hasText(issue.path("key").asText())) {
                refs.add(issue.path("key").asText());
                continue;
            }
            if (issue.has("id")) {
                refs.add(issue.path("id").asText());
            }
        }
        return refs;
    }

    private List<JiraIssueRecord> mapBulkFetchIssues(JsonNode response) {
        if (response == null || !response.has("issues")) {
            return List.of();
        }

        List<JiraIssueRecord> records = new ArrayList<>();
        for (JsonNode issue : response.get("issues")) {
            records.add(mapIssueNode(issue));
        }
        return records;
    }

    private JiraIssueRecord mapIssueNode(JsonNode issue) {
        String key = issue.path("key").asText();
        if (!StringUtils.hasText(key) && issue.has("id")) {
            key = issue.path("id").asText();
        }

        JsonNode fields = issue.path("fields");
        String description = extractDescription(fields.path("description"));
        String status = fields.path("status").path("name").asText("");
        String resolution = fields.path("resolution").isNull() || fields.path("resolution").isMissingNode()
                ? ""
                : fields.path("resolution").path("name").asText("");

        return JiraIssueRecord.builder()
                .ticketId(key)
                .title(fields.path("summary").asText(""))
                .description(description)
                .status(status)
                .resolution(resolution)
                .build();
    }

    private String readNextPageToken(JsonNode response) {
        if (response == null || !response.has("nextPageToken") || response.get("nextPageToken").isNull()) {
            return null;
        }
        String token = response.get("nextPageToken").asText();
        return StringUtils.hasText(token) ? token : null;
    }

    private String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return "";
        }
        if (descriptionNode.isTextual()) {
            return descriptionNode.asText();
        }
        if (descriptionNode.has("content")) {
            return StreamSupport.stream(descriptionNode.get("content").spliterator(), false)
                    .map(node -> node.path("content").isArray()
                            ? StreamSupport.stream(node.get("content").spliterator(), false)
                            .map(inner -> inner.path("text").asText(""))
                            .collect(Collectors.joining(" "))
                            : node.path("text").asText(""))
                    .collect(Collectors.joining("\n"));
        }
        return descriptionNode.toString();
    }

    private void validateConfiguration() {
        if (!properties.isConfigured()) {
            throw new BadRequestException(
                    "Jira is not configured. Set JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN environment variables.");
        }
    }

    private String basicAuthHeader() {
        String credentials = properties.getEmail() + ":" + properties.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
