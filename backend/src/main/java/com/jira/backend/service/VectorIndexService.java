package com.jira.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.client.OllamaClient;
import com.jira.backend.client.QdrantVectorClient;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.dto.SimilarIssueDto;
import com.jira.backend.model.JiraIssueRecord;
import com.jira.backend.model.VectorSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VectorIndexService implements VectorIndexPort {

    private final OllamaClient ollamaClient;
    private final QdrantVectorClient qdrantVectorClient;
    private final QdrantProperties qdrantProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void indexIssue(JiraIssueRecord issue) {
        List<Float> vector = ollamaClient.embed(issue.toIndexableText());
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticketId", issue.getTicketId());
        payload.put("title", issue.getTitle());
        payload.put("description", issue.getDescription());
        payload.put("status", issue.getStatus());
        payload.put("resolution", issue.getResolution());
        payload.put("indexableText", issue.toIndexableText());

        qdrantVectorClient.upsertIssue(toPointId(issue.getTicketId()), vector, payload);
    }

    @Override
    public List<SimilarIssueDto> findSimilarIssues(List<Float> queryVector, int topK) {
        return qdrantVectorClient.searchIssues(queryVector, topK).stream()
                .map(this::toSimilarIssue)
                .toList();
    }

    @Override
    public AnalyzeResponseDto findCachedAnalysis(List<Float> queryVector) {
        List<VectorSearchResult> cached = qdrantVectorClient.searchCache(queryVector, 1);
        if (cached.isEmpty()) {
            return null;
        }

        VectorSearchResult best = cached.get(0);
        if (best.getScore() < qdrantProperties.getCacheSimilarityThreshold()) {
            return null;
        }

        Object analysisJson = best.getPayload().get("analysisJson");
        if (analysisJson == null) {
            return null;
        }

        try {
            return objectMapper.readValue(analysisJson.toString(), AnalyzeResponseDto.class);
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void cacheAnalysis(String normalizedQuery, List<Float> queryVector, AnalyzeResponseDto response) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("normalizedQuery", normalizedQuery);
            payload.put("analysisJson", objectMapper.writeValueAsString(response));

            String cachePointId = toCachePointId(normalizedQuery);
            qdrantVectorClient.upsertSearchCache(cachePointId, queryVector, payload);
        } catch (Exception ignored) {
            // Caching must not break the main analyze flow.
        }
    }

    @Override
    public List<Float> embedText(String text) {
        return ollamaClient.embed(text);
    }

    private SimilarIssueDto toSimilarIssue(VectorSearchResult result) {
        Map<String, Object> payload = result.getPayload();
        return SimilarIssueDto.builder()
                .ticketId(stringValue(payload.get("ticketId")))
                .title(stringValue(payload.get("title")))
                .description(stringValue(payload.get("description")))
                .status(stringValue(payload.get("status")))
                .resolution(stringValue(payload.get("resolution")))
                .similarityScore(result.getScore())
                .build();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String toPointId(String ticketId) {
        return UUID.nameUUIDFromBytes(("issue:" + ticketId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String toCachePointId(String normalizedQuery) {
        return UUID.nameUUIDFromBytes(("cache:" + normalizedQuery).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
