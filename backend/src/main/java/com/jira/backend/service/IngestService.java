package com.jira.backend.service;

import com.jira.backend.client.JiraPort;
import com.jira.backend.service.VectorIndexPort;
import com.jira.backend.dto.IngestRequestDto;
import com.jira.backend.dto.IngestResponseDto;
import com.jira.backend.model.JiraIssueRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IngestService {

    private final JiraPort jiraPort;
    private final VectorIndexPort vectorIndexPort;

    public IngestResponseDto ingestFromJira(IngestRequestDto request) {
        List<JiraIssueRecord> issues = jiraPort.searchIssues(request.getJql(), request.getMaxResults());

        int indexed = 0;
        for (JiraIssueRecord issue : issues) {
            vectorIndexPort.indexIssue(issue);
            indexed++;
        }

        return IngestResponseDto.builder()
                .issuesFetched(issues.size())
                .issuesIndexed(indexed)
                .message("Indexed %d Jira issues into Qdrant collection '%s'."
                        .formatted(indexed, "jira_issues"))
                .build();
    }
}
