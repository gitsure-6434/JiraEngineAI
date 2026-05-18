package com.jira.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.client.JiraPort;
import com.jira.backend.client.OllamaPort;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.dto.AnalyzeRequestDto;
import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.dto.SimilarIssueDto;
import com.jira.backend.exception.AiServiceException;
import com.jira.backend.model.JiraIssueRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AnalyzeService implements AnalyzePort {

    private final JiraPort jiraPort;
    private final OllamaPort ollamaPort;
    private final VectorIndexPort vectorIndexPort;
    private final QdrantProperties qdrantProperties;
    private final ObjectMapper objectMapper;

    @Override
    public AnalyzeResponseDto analyze(AnalyzeRequestDto request) {
        String normalizedQuery = normalize(request.getText());
        int topK = request.getTopK() != null ? request.getTopK() : qdrantProperties.getTopKDefault();

        List<Float> queryVector = vectorIndexPort.embedText(request.getText());

        AnalyzeResponseDto cached = vectorIndexPort.findCachedAnalysis(queryVector);
        if (cached != null) {
            return AnalyzeResponseDto.builder()
                    .similarIssues(cached.getSimilarIssues())
                    .rootCauseSummary(cached.getRootCauseSummary())
                    .recommendedFix(cached.getRecommendedFix())
                    .recommendedCodePatch(cached.getRecommendedCodePatch())
                    .reproductionSteps(cached.getReproductionSteps())
                    .relatedTicketIds(cached.getRelatedTicketIds())
                    .fromCache(true)
                    .build();
        }

        List<JiraIssueRecord> referencedTickets = fetchReferencedTickets(request.getTicketIds());
        List<SimilarIssueDto> similarIssues =
                vectorIndexPort.findSimilarIssues(queryVector, request.getText(), topK);

        String similarContext = PromptBuilder.buildSimilarIssuesContext(
                similarIssues.stream()
                        .map(issue -> """
                                Ticket: %s
                                Title: %s
                                Status: %s
                                Resolution: %s
                                Description: %s
                                Comments: %s
                                Similarity: %.4f
                                """.formatted(
                                issue.getTicketId(),
                                issue.getTitle(),
                                issue.getStatus(),
                                issue.getResolution(),
                                issue.getDescription(),
                                issue.getComments(),
                                issue.getSimilarityScore()))
                        .toList());

        String prompt = PromptBuilder.buildAnalysisPrompt(request.getText(), referencedTickets, similarContext);
        String llmRaw = ollamaPort.chat(prompt);
        AnalyzeResponseDto response = mergeLlmResponse(similarIssues, llmRaw);

        vectorIndexPort.cacheAnalysis(normalizedQuery, queryVector, response);
        return response;
    }

    private List<JiraIssueRecord> fetchReferencedTickets(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return List.of();
        }
        try {
            return jiraPort.fetchIssuesByKeys(ticketIds);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private AnalyzeResponseDto mergeLlmResponse(List<SimilarIssueDto> similarIssues, String llmRaw) {
        try {
            String json = extractJson(llmRaw);
            JsonNode node = objectMapper.readTree(json);

            List<String> reproductionSteps = readStringList(node.path("reproductionSteps"));
            List<String> relatedTicketIds = readStringList(node.path("relatedTicketIds"));

            if (relatedTicketIds.isEmpty() && !similarIssues.isEmpty()) {
                relatedTicketIds = List.of(similarIssues.get(0).getTicketId());
            } else {
                relatedTicketIds = filterToKnownSimilarTickets(relatedTicketIds, similarIssues);
                if (relatedTicketIds.isEmpty() && !similarIssues.isEmpty()) {
                    relatedTicketIds = List.of(similarIssues.get(0).getTicketId());
                }
            }

            List<SimilarIssueDto> responseSimilarIssues = filterSimilarIssuesForResponse(similarIssues, relatedTicketIds);

            String recommendedFix = node.path("recommendedFix").asText("");
            if (isWeakFix(recommendedFix) && !responseSimilarIssues.isEmpty()) {
                recommendedFix = buildFixFromSimilarIssue(responseSimilarIssues.get(0));
            }

            return AnalyzeResponseDto.builder()
                    .similarIssues(responseSimilarIssues)
                    .rootCauseSummary(node.path("rootCauseSummary").asText(""))
                    .recommendedFix(recommendedFix)
                    .recommendedCodePatch(node.path("recommendedCodePatch").asText(""))
                    .reproductionSteps(reproductionSteps)
                    .relatedTicketIds(relatedTicketIds)
                    .fromCache(false)
                    .build();
        } catch (Exception ex) {
            List<String> relatedTicketIds = similarIssues.isEmpty()
                    ? List.of()
                    : List.of(similarIssues.get(0).getTicketId());
            String recommendedFix = similarIssues.isEmpty()
                    ? llmRaw
                    : buildFixFromSimilarIssue(similarIssues.get(0));
            return AnalyzeResponseDto.builder()
                    .similarIssues(filterSimilarIssuesForResponse(similarIssues, relatedTicketIds))
                    .rootCauseSummary("Analysis completed with partial structured output.")
                    .recommendedFix(recommendedFix)
                    .recommendedCodePatch("")
                    .reproductionSteps(List.of())
                    .relatedTicketIds(relatedTicketIds)
                    .fromCache(false)
                    .build();
        }
    }

    private boolean isWeakFix(String recommendedFix) {
        if (!StringUtils.hasText(recommendedFix)) {
            return true;
        }
        String lower = recommendedFix.toLowerCase(Locale.ROOT);
        return lower.contains("no recommended fix") || lower.contains("not found");
    }

    private String buildFixFromSimilarIssue(SimilarIssueDto issue) {
        if (StringUtils.hasText(issue.getComments())) {
            return "Historical fix from %s (see Jira comments): %s"
                    .formatted(issue.getTicketId(), issue.getComments());
        }
        if (StringUtils.hasText(issue.getResolution())) {
            return "Historical resolution from %s: %s".formatted(issue.getTicketId(), issue.getResolution());
        }
        if (StringUtils.hasText(issue.getDescription())) {
            return "See historical ticket %s: %s".formatted(issue.getTicketId(), issue.getDescription());
        }
        return "Review historical ticket %s (%s) for a known fix."
                .formatted(issue.getTicketId(), issue.getTitle());
    }

    private List<String> filterToKnownSimilarTickets(List<String> ticketIds, List<SimilarIssueDto> similarIssues) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return List.of();
        }
        var knownIds = similarIssues.stream()
                .map(SimilarIssueDto::getTicketId)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());

        return ticketIds.stream()
                .filter(StringUtils::hasText)
                .filter(id -> knownIds.contains(id.trim().toUpperCase()))
                .distinct()
                .toList();
    }

    private List<SimilarIssueDto> filterSimilarIssuesForResponse(
            List<SimilarIssueDto> similarIssues, List<String> relatedTicketIds) {
        if (relatedTicketIds == null || relatedTicketIds.isEmpty()) {
            return similarIssues;
        }
        var related = relatedTicketIds.stream()
                .filter(StringUtils::hasText)
                .map(id -> id.trim().toUpperCase())
                .collect(java.util.stream.Collectors.toSet());

        List<SimilarIssueDto> matched = similarIssues.stream()
                .filter(issue -> related.contains(issue.getTicketId().toUpperCase()))
                .toList();
        return matched.isEmpty() ? similarIssues : matched;
    }

    private List<String> readStringList(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        arrayNode.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiServiceException("LLM response did not contain JSON");
        }
        return raw.substring(start, end + 1);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
