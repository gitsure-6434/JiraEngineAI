package com.jira.backend.service;

import com.jira.backend.client.JiraPort;
import com.jira.backend.client.OllamaPort;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.dto.AnalyzeRequestDto;
import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.dto.SimilarIssueDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeServiceTest {

    @Mock
    private JiraPort jiraPort;

    @Mock
    private OllamaPort ollamaPort;

    @Mock
    private VectorIndexPort vectorIndexPort;

    private final QdrantProperties qdrantProperties = new QdrantProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnalyzeService analyzeService;

    @BeforeEach
    void setUp() {
        analyzeService = new AnalyzeService(jiraPort, ollamaPort, vectorIndexPort, qdrantProperties, objectMapper);
    }

    @Test
    void returnsCachedResultWhenAvailable() {
        AnalyzeRequestDto request = new AnalyzeRequestDto();
        request.setText("login fails with 401");

        AnalyzeResponseDto cached = AnalyzeResponseDto.builder()
                .similarIssues(List.of())
                .rootCauseSummary("Cached root cause")
                .recommendedFix("Cached fix")
                .recommendedCodePatch("")
                .reproductionSteps(List.of())
                .relatedTicketIds(List.of())
                .fromCache(true)
                .build();

        when(vectorIndexPort.embedText(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(vectorIndexPort.findCachedAnalysis(any())).thenReturn(cached);

        AnalyzeResponseDto response = analyzeService.analyze(request);

        assertThat(response.getRootCauseSummary()).isEqualTo("Cached root cause");
        assertThat(response.isFromCache()).isTrue();
        verify(ollamaPort, never()).chat(anyString());
    }

    @Test
    void callsOllamaWhenCacheMisses() {
        AnalyzeRequestDto request = new AnalyzeRequestDto();
        request.setText("NullPointerException in login service");

        when(vectorIndexPort.embedText(anyString())).thenReturn(List.of(0.5f, 0.6f));
        when(vectorIndexPort.findCachedAnalysis(any())).thenReturn(null);
        when(vectorIndexPort.findSimilarIssues(any(), anyInt())).thenReturn(List.of(
                SimilarIssueDto.builder()
                        .ticketId("PROJ-07")
                        .title("Login NPE")
                        .description("NPE in token parser")
                        .status("Done")
                        .resolution("Fixed parser")
                        .similarityScore(0.88)
                        .build()));
        when(ollamaPort.chat(anyString())).thenReturn("""
                {
                  "rootCauseSummary": "Null token parser",
                  "recommendedFix": "Add null checks",
                  "recommendedCodePatch": "if (token == null) throw new IllegalArgumentException();",
                  "reproductionSteps": ["Open login"],
                  "relatedTicketIds": ["PROJ-07"]
                }
                """);

        AnalyzeResponseDto response = analyzeService.analyze(request);

        assertThat(response.getRootCauseSummary()).contains("Null token");
        assertThat(response.getRecommendedCodePatch()).contains("IllegalArgumentException");
        assertThat(response.isFromCache()).isFalse();
        verify(vectorIndexPort).cacheAnalysis(anyString(), any(), any());
    }
}
