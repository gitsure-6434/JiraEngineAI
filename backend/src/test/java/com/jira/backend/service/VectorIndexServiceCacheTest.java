package com.jira.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.client.OllamaClient;
import com.jira.backend.client.QdrantVectorClient;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.dto.SimilarIssueDto;
import com.jira.backend.model.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorIndexServiceCacheTest {

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private QdrantVectorClient qdrantVectorClient;

    private VectorIndexService vectorIndexService;

    @BeforeEach
    void setUp() {
        vectorIndexService = new VectorIndexService(
                ollamaClient,
                qdrantVectorClient,
                new QdrantProperties(),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void invalidateCacheForTicketDeletesEntriesReferencingTicket() throws Exception {
        AnalyzeResponseDto cached = AnalyzeResponseDto.builder()
                .similarIssues(List.of(
                        SimilarIssueDto.builder().ticketId("SCRUM-7").title("OAuth").similarityScore(0.9).build()))
                .rootCauseSummary("summary")
                .recommendedFix("fix")
                .relatedTicketIds(List.of())
                .fromCache(true)
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("analysisJson", new ObjectMapper().writeValueAsString(cached));

        when(qdrantVectorClient.scrollAllSearchCache()).thenReturn(List.of(
                VectorSearchResult.builder().pointId("cache-point-1").score(0.0).payload(payload).build(),
                VectorSearchResult.builder()
                        .pointId("cache-point-2")
                        .score(0.0)
                        .payload(Map.of("analysisJson", "{\"similarIssues\":[],\"recommendedFix\":\"other\"}"))
                        .build()));

        vectorIndexService.invalidateCacheForTicket("scrum-7");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(qdrantVectorClient).deleteSearchCachePoints(captor.capture());
        assertThat(captor.getValue()).containsExactly("cache-point-1");
    }
}
