package com.jira.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.exception.BadRequestException;
import com.jira.backend.exception.JiraIntegrationException;
import com.jira.backend.model.JiraIssueRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Component
public class JiraApiClient implements JiraPort {

    private static final int PAGE_SIZE = 100;
    private static final int BULK_FETCH_SIZE = 100;
    private static final List<String> ISSUE_FIELDS =
            List.of("summary", "description", "status", "resolution");

    private final RestTemplate jiraRestTemplate;
    private final JiraProperties properties;

    public JiraApiClient(@Qualifier("jiraRestTemplate") RestTemplate jiraRestTemplate, JiraProperties properties) {
        this.jiraRestTemplate = jiraRestTemplate;
        this.properties = properties;
    }

    @Override
    public List<JiraIssueRecord> searchIssues(String jql, int maxResults) {
        validateConfiguration();

        try {
            List<String> issueRefs = collectIssueRefsFromSearch(jql, maxResults);
            if (issueRefs.isEmpty()) {
                log.warn("Jira returned 0 issues for jql='{}'", jql);
                return List.of();
            }
            return bulkFetchIssueDetails(issueRefs);
        } catch (HttpStatusCodeException ex) {
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

    private List<String> collectIssueRefsFromSearch(String jql, int maxResults) {
        List<String> issueRefs = new ArrayList<>();
        String nextPageToken = null;

        while (issueRefs.size() < maxResults) {
            int pageSize = Math.min(maxResults - issueRefs.size(), PAGE_SIZE);
            JsonNode response = executeJqlSearch(jql, pageSize, nextPageToken);
            List<String> pageRefs = extractIssueRefs(response);
            issueRefs.addAll(pageRefs);

            log.debug("Jira search page: jql='{}', pageSize={}, found={}, isLast={}",
                    jql, pageSize, pageRefs.size(), response.path("isLast").asBoolean(false));

            nextPageToken = readNextPageToken(response);
            boolean isLast = response.has("isLast")
                    ? response.path("isLast").asBoolean(false)
                    : !StringUtils.hasText(nextPageToken);

            if (isLast) {
                break;
            }
        }

        return issueRefs.size() > maxResults ? issueRefs.subList(0, maxResults) : issueRefs;
    }

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

        JsonNode response = postJson("/rest/api/3/search/jql", body);
        assertValidSearchResponse(response);
        return response;
    }

    private JsonNode executeBulkFetch(List<String> issueIdsOrKeys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issueIdsOrKeys", issueIdsOrKeys);
        body.put("fields", ISSUE_FIELDS);
        body.put("fieldsByKeys", false);

        return postJson("/rest/api/3/issue/bulkfetch", body);
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBasicAuth(properties.getEmail(), properties.getApiToken(), StandardCharsets.UTF_8);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> response = jiraRestTemplate.exchange(
                path, HttpMethod.POST, entity, JsonNode.class);
        return response.getBody();
    }

    private List<String> extractIssueRefs(JsonNode response) {
        if (response == null || !response.has("issues") || !response.get("issues").isArray()) {
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
            records.add(enrichWithComments(mapIssueNode(issue)));
        }
        return records;
    }

    private JiraIssueRecord enrichWithComments(JiraIssueRecord record) {
        if (!StringUtils.hasText(record.getTicketId())) {
            return record;
        }
        try {
            String comments = fetchCommentText(record.getTicketId());
            return JiraIssueRecord.builder()
                    .ticketId(record.getTicketId())
                    .title(record.getTitle())
                    .description(record.getDescription())
                    .status(record.getStatus())
                    .resolution(record.getResolution())
                    .comments(comments)
                    .build();
        } catch (Exception ex) {
            log.warn("Could not load comments for {}: {}", record.getTicketId(), ex.getMessage());
            return record;
        }
    }

    private String fetchCommentText(String issueKey) {
        JsonNode response = getJson("/rest/api/3/issue/" + issueKey + "/comment");
        if (response == null || !response.has("comments") || !response.get("comments").isArray()) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        for (JsonNode comment : response.get("comments")) {
            String text = extractDescription(comment.path("body"));
            if (StringUtils.hasText(text)) {
                if (!combined.isEmpty()) {
                    combined.append("\n---\n");
                }
                combined.append(text);
            }
        }
        return combined.toString();
    }

    private JsonNode getJson(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBasicAuth(properties.getEmail(), properties.getApiToken(), StandardCharsets.UTF_8);
        ResponseEntity<JsonNode> response = jiraRestTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return response.getBody();
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
        if (properties.getApiToken() != null && properties.getApiToken().startsWith("JIRA_API_TOKEN=")) {
            throw new BadRequestException(
                    "JIRA_API_TOKEN looks misconfigured (includes 'JIRA_API_TOKEN=' prefix). "
                            + "Set the env var to the token value only, e.g. copy from .env.example.");
        }
        if (!properties.isConfigured()) {
            throw new BadRequestException(
                    "Jira is not configured. Copy .env.example to .env in the project root (or set "
                            + "JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN), then restart the app.");
        }
    }

    private void assertValidSearchResponse(JsonNode response) {
        if (response == null) {
            throw new JiraIntegrationException("Jira search returned an empty response body");
        }
        if (response.has("errorMessages") && response.get("errorMessages").isArray()) {
            List<String> errors = new ArrayList<>();
            response.get("errorMessages").forEach(node -> errors.add(node.asText()));
            if (!errors.isEmpty()) {
                throw new JiraIntegrationException("Jira search failed: " + String.join("; ", errors));
            }
        }
        if (response.has("warningMessages") && response.get("warningMessages").isArray()) {
            response.get("warningMessages").forEach(node ->
                    log.warn("Jira search warning: {}", node.asText()));
        }
    }
}
