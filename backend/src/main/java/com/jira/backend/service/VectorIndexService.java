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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
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
        payload.put("comments", issue.getComments());
        payload.put("status", issue.getStatus());
        payload.put("resolution", issue.getResolution());
        payload.put("indexableText", issue.toIndexableText());

        qdrantVectorClient.upsertIssue(toPointId(issue.getTicketId()), vector, payload);
        invalidateCacheForTicket(issue.getTicketId());
    }

    @Override
    public List<SimilarIssueDto> findSimilarIssues(List<Float> queryVector, String queryText, int topK) {
        long indexedCount = qdrantVectorClient.countIssues();
        List<VectorSearchResult> candidates = qdrantVectorClient.searchIssues(queryVector, topK, 0.0);

        log.debug("Qdrant issue search: indexed={}, candidates={}, topScore={}",
                indexedCount,
                candidates.size(),
                candidates.isEmpty() ? "n/a" : candidates.get(0).getScore());

        List<SimilarIssueDto> results = rankVectorCandidates(candidates, topK);
        if (!results.isEmpty()) {
            return results;
        }

        if (indexedCount == 0) {
            log.warn("No issues in Qdrant collection '{}'. Run POST /api/v1/ingest first.",
                    qdrantProperties.getIssuesCollection());
            return List.of();
        }

        log.info("Vector search returned no matches above threshold; using text fallback for query");
        return textSearchFallback(queryText, topK);
    }

    private List<SimilarIssueDto> rankVectorCandidates(List<VectorSearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        double threshold = qdrantProperties.getIssueSimilarityThreshold();
        List<SimilarIssueDto> aboveThreshold = candidates.stream()
                .filter(result -> result.getScore() >= threshold)
                .limit(topK)
                .map(this::toSimilarIssue)
                .toList();
        if (!aboveThreshold.isEmpty()) {
            return aboveThreshold;
        }

        return List.of(toSimilarIssue(candidates.get(0)));
    }

    private List<SimilarIssueDto> textSearchFallback(String queryText, int topK) {
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(queryText);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<ScoredPayload> scored = new ArrayList<>();
        for (VectorSearchResult point : qdrantVectorClient.scrollAllIssues(500)) {
            String indexableText = stringValue(point.getPayload().get("indexableText"));
            if (!StringUtils.hasText(indexableText)) {
                indexableText = buildIndexableFromPayload(point.getPayload());
            }
            double score = textOverlapScore(queryTokens, tokenize(indexableText));
            if (score > 0.0) {
                scored.add(new ScoredPayload(point, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredPayload::score).reversed())
                .limit(topK)
                .map(entry -> toSimilarIssueWithScore(entry.point(), entry.score()))
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
            AnalyzeResponseDto cachedResponse = objectMapper.readValue(analysisJson.toString(), AnalyzeResponseDto.class);
            if (isWeakCachedResult(cachedResponse)) {
                log.debug("Ignoring weak cached analysis (no similar issues / no fix)");
                return null;
            }
            return cachedResponse;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isWeakCachedResult(AnalyzeResponseDto cached) {
        boolean noSimilar = cached.getSimilarIssues() == null || cached.getSimilarIssues().isEmpty();
        String fix = cached.getRecommendedFix() == null ? "" : cached.getRecommendedFix().toLowerCase(Locale.ROOT);
        boolean weakFix = !StringUtils.hasText(fix) || fix.contains("no recommended fix");
        return noSimilar && weakFix;
    }

    @Override
    public void cacheAnalysis(String normalizedQuery, List<Float> queryVector, AnalyzeResponseDto response) {
        if (isWeakCachedResult(response)) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("normalizedQuery", normalizedQuery);
            payload.put("analysisJson", objectMapper.writeValueAsString(response));
            payload.put("referencedTicketIds", collectReferencedTicketIds(response));

            String cachePointId = toCachePointId(normalizedQuery);
            qdrantVectorClient.upsertSearchCache(cachePointId, queryVector, payload);
        } catch (Exception ignored) {
            // Caching must not break the main analyze flow.
        }
    }

    @Override
    public void invalidateCacheForTicket(String ticketId) {
        if (!StringUtils.hasText(ticketId)) {
            return;
        }

        String normalizedTicketId = ticketId.trim().toUpperCase(Locale.ROOT);
        List<String> pointIdsToDelete = new ArrayList<>();

        for (VectorSearchResult point : qdrantVectorClient.scrollAllSearchCache()) {
            if (cacheEntryReferencesTicket(point, normalizedTicketId)) {
                pointIdsToDelete.add(point.getPointId());
            }
        }

        if (pointIdsToDelete.isEmpty()) {
            return;
        }

        qdrantVectorClient.deleteSearchCachePoints(pointIdsToDelete);
        log.info("Invalidated {} analyze cache entries referencing ticket {}", pointIdsToDelete.size(), normalizedTicketId);
    }

    @Override
    public List<Float> embedText(String text) {
        return ollamaClient.embed(text);
    }

    private SimilarIssueDto toSimilarIssue(VectorSearchResult result) {
        return toSimilarIssueWithScore(result, result.getScore());
    }

    private SimilarIssueDto toSimilarIssueWithScore(VectorSearchResult result, double score) {
        Map<String, Object> payload = result.getPayload();
        return SimilarIssueDto.builder()
                .ticketId(stringValue(payload.get("ticketId")))
                .title(stringValue(payload.get("title")))
                .description(stringValue(payload.get("description")))
                .comments(stringValue(payload.get("comments")))
                .status(stringValue(payload.get("status")))
                .resolution(stringValue(payload.get("resolution")))
                .similarityScore(score)
                .build();
    }

    private String buildIndexableFromPayload(Map<String, Object> payload) {
        return """
                Ticket: %s
                Title: %s
                Description: %s
                Comments: %s
                """.formatted(
                stringValue(payload.get("ticketId")),
                stringValue(payload.get("title")),
                stringValue(payload.get("description")),
                stringValue(payload.get("comments")));
    }

    private double textOverlapScore(Set<String> queryTokens, Set<String> documentTokens) {
        if (queryTokens.isEmpty() || documentTokens.isEmpty()) {
            return 0.0;
        }
        long matches = queryTokens.stream().filter(documentTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toCollection(HashSet::new));
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

    private List<String> collectReferencedTicketIds(AnalyzeResponseDto response) {
        Set<String> ticketIds = new HashSet<>();
        if (response.getRelatedTicketIds() != null) {
            response.getRelatedTicketIds().stream()
                    .filter(StringUtils::hasText)
                    .map(id -> id.trim().toUpperCase(Locale.ROOT))
                    .forEach(ticketIds::add);
        }
        if (response.getSimilarIssues() != null) {
            response.getSimilarIssues().stream()
                    .map(SimilarIssueDto::getTicketId)
                    .filter(StringUtils::hasText)
                    .map(id -> id.trim().toUpperCase(Locale.ROOT))
                    .forEach(ticketIds::add);
        }
        return ticketIds.stream().sorted().toList();
    }

    private boolean cacheEntryReferencesTicket(VectorSearchResult point, String ticketId) {
        Map<String, Object> payload = point.getPayload();
        if (payload == null) {
            return false;
        }

        Object referenced = payload.get("referencedTicketIds");
        if (referenced instanceof List<?> referencedList) {
            for (Object entry : referencedList) {
                if (ticketId.equalsIgnoreCase(String.valueOf(entry))) {
                    return true;
                }
            }
        }

        Object analysisJson = payload.get("analysisJson");
        if (analysisJson == null) {
            return false;
        }

        try {
            AnalyzeResponseDto cached = objectMapper.readValue(analysisJson.toString(), AnalyzeResponseDto.class);
            if (cached.getRelatedTicketIds() != null) {
                for (String relatedId : cached.getRelatedTicketIds()) {
                    if (ticketId.equalsIgnoreCase(relatedId)) {
                        return true;
                    }
                }
            }
            if (cached.getSimilarIssues() != null) {
                for (SimilarIssueDto similar : cached.getSimilarIssues()) {
                    if (ticketId.equalsIgnoreCase(similar.getTicketId())) {
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Could not parse cached analysis for invalidation check: {}", ex.getMessage());
        }

        return analysisJson.toString().toUpperCase(Locale.ROOT).contains(ticketId);
    }

    private record ScoredPayload(VectorSearchResult point, double score) {
    }
}
