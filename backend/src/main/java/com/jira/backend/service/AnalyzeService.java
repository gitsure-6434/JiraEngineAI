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
        List<SimilarIssueDto> similarIssues = vectorIndexPort.findSimilarIssues(queryVector, topK);

        String similarContext = PromptBuilder.buildSimilarIssuesContext(
                similarIssues.stream()
                        .map(issue -> """
                                Ticket: %s
                                Title: %s
                                Status: %s
                                Resolution: %s
                                Description: %s
                                Similarity: %.4f
                                """.formatted(
                                issue.getTicketId(),
                                issue.getTitle(),
                                issue.getStatus(),
                                issue.getResolution(),
                                issue.getDescription(),
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

            if (relatedTicketIds.isEmpty()) {
                relatedTicketIds = similarIssues.stream()
                        .map(SimilarIssueDto::getTicketId)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList();
            }

            return AnalyzeResponseDto.builder()
                    .similarIssues(similarIssues)
                    .rootCauseSummary(node.path("rootCauseSummary").asText(""))
                    .recommendedFix(node.path("recommendedFix").asText(""))
                    .recommendedCodePatch(node.path("recommendedCodePatch").asText(""))
                    .reproductionSteps(reproductionSteps)
                    .relatedTicketIds(relatedTicketIds)
                    .fromCache(false)
                    .build();
        } catch (Exception ex) {
            return AnalyzeResponseDto.builder()
                    .similarIssues(similarIssues)
                    .rootCauseSummary("Analysis completed with partial structured output.")
                    .recommendedFix(llmRaw)
                    .recommendedCodePatch("")
                    .reproductionSteps(List.of())
                    .relatedTicketIds(similarIssues.stream().map(SimilarIssueDto::getTicketId).toList())
                    .fromCache(false)
                    .build();
        }
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
