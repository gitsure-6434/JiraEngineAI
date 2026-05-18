package com.jira.backend.service;

import com.jira.backend.client.JiraPort;
import com.jira.backend.model.JiraIssueRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JiraIssueSyncService {

    private final JiraPort jiraPort;
    private final VectorIndexPort vectorIndexPort;

    @Async("jiraWebhookExecutor")
    public void syncByKeyAsync(String issueKey) {
        syncByKey(issueKey);
    }

    public boolean syncByKey(String issueKey) {
        if (!StringUtils.hasText(issueKey)) {
            return false;
        }

        List<JiraIssueRecord> records = jiraPort.fetchIssuesByKeys(List.of(issueKey.trim().toUpperCase()));
        if (records.isEmpty()) {
            log.warn("Jira webhook sync: no issue returned for key {}", issueKey);
            return false;
        }

        JiraIssueRecord issue = records.get(0);
        vectorIndexPort.indexIssue(issue);
        log.info("Indexed issue {} into Qdrant (create or update via upsert)", issue.getTicketId());
        return true;
    }
}
