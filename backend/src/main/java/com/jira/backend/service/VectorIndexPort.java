package com.jira.backend.service;

import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.dto.SimilarIssueDto;
import com.jira.backend.model.JiraIssueRecord;

import java.util.List;

public interface VectorIndexPort {

    void indexIssue(JiraIssueRecord issue);

    List<SimilarIssueDto> findSimilarIssues(List<Float> queryVector, String queryText, int topK);

    AnalyzeResponseDto findCachedAnalysis(List<Float> queryVector);

    void cacheAnalysis(String normalizedQuery, List<Float> queryVector, AnalyzeResponseDto response);

    void invalidateCacheForTicket(String ticketId);

    List<Float> embedText(String text);
}
