package com.jira.backend.controller;

import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.exception.GlobalExceptionHandler;
import com.jira.backend.service.AnalyzePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyzeController.class)
@Import(GlobalExceptionHandler.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyzePort analyzePort;

    @Test
    void analyzesTextOnlyRequest() throws Exception {
        when(analyzePort.analyze(any())).thenReturn(
                AnalyzeResponseDto.builder()
                        .similarIssues(List.of())
                        .rootCauseSummary("Token refresh race condition")
                        .recommendedFix("Validate token expiry before refresh call")
                        .recommendedCodePatch("if (token.isExpired()) { refresh(); }")
                        .reproductionSteps(List.of("Login", "Wait for token expiry", "Call protected API"))
                        .relatedTicketIds(List.of("PROJ-101"))
                        .fromCache(false)
                        .build());

        mockMvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "401 after token refresh on login API"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootCauseSummary").value("Token refresh race condition"))
                .andExpect(jsonPath("$.recommendedFix").exists())
                .andExpect(jsonPath("$.fromCache").value(false));
    }
}
