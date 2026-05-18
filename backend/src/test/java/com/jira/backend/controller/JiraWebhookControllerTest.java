package com.jira.backend.controller;

import com.jira.backend.config.AppProperties;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.dto.JiraWebhookResponseDto;
import com.jira.backend.exception.GlobalExceptionHandler;
import com.jira.backend.service.JiraWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JiraWebhookController.class)
@Import({GlobalExceptionHandler.class, JiraProperties.class, AppProperties.class})
class JiraWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JiraWebhookService jiraWebhookService;

    @Test
    void returnsWebhookInfo() throws Exception {
        mockMvc.perform(get("/api/v1/jira/webhook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value("http://localhost:8989/api/v1/jira/webhook"))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.syncProjectKey").value("SCRUM"))
                .andExpect(jsonPath("$.jqlFilterHint").value("project = SCRUM"));
    }

    @Test
    void acceptsWebhookPayload() throws Exception {
        when(jiraWebhookService.handleWebhook(any(), eq(null))).thenReturn(
                JiraWebhookResponseDto.builder()
                        .webhookEvent("jira:issue_updated")
                        .issueKey("SCRUM-7")
                        .status("accepted")
                        .message("Issue queued for vector index sync (create or update)")
                        .build());

        mockMvc.perform(post("/api/v1/jira/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "webhookEvent": "jira:issue_updated",
                                  "issue": {
                                    "key": "SCRUM-7",
                                    "fields": { "project": { "key": "SCRUM" } }
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.issueKey").value("SCRUM-7"));
    }
}
