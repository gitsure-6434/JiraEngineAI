package com.jira.backend.client;

import com.jira.backend.model.JiraIssueRecord;

import java.util.List;

public interface JiraPort {

    List<JiraIssueRecord> searchIssues(String jql, int maxResults);

    List<JiraIssueRecord> fetchIssuesByKeys(List<String> ticketIds);
}
