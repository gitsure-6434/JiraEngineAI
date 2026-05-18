package com.jira.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.backend.config.AppProperties;
import com.jira.backend.config.JiraProperties;
import com.jira.backend.dto.JiraWebhookInfoDto;
import com.jira.backend.dto.JiraWebhookResponseDto;
import com.jira.backend.service.JiraWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jira/webhook")
@RequiredArgsConstructor
public class JiraWebhookController {

    private static final String SECRET_HEADER = "X-Webhook-Secret";

    private final JiraWebhookService jiraWebhookService;
    private final JiraProperties jiraProperties;
    private final AppProperties appProperties;

    @GetMapping
    public JiraWebhookInfoDto webhookInfo(HttpServletRequest request) {
        return JiraWebhookInfoDto.builder()
                .webhookUrl(resolveWebhookUrl(request))
                .method("POST")
                .syncProjectKey(jiraProperties.getSyncProjectKey())
                .supportedEvents(List.of(
                        "jira:issue_created",
                        "jira:issue_updated",
                        "comment_created",
                        "comment_updated"))
                .jqlFilterHint("project = " + jiraProperties.getSyncProjectKey())
                .secretHeader(SECRET_HEADER)
                .build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JiraWebhookResponseDto receiveWebhook(
            @RequestBody JsonNode payload,
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader,
            @RequestParam(value = "secret", required = false) String secretQuery) {
        String secret = StringUtils.hasText(secretHeader) ? secretHeader : secretQuery;
        return jiraWebhookService.handleWebhook(payload, secret);
    }

    private String resolveWebhookUrl(HttpServletRequest request) {
        if (appProperties.hasPublicBaseUrl()) {
            String base = appProperties.getPublicBaseUrl().replaceAll("/+$", "");
            return base + "/api/v1/jira/webhook";
        }
        StringBuffer url = request.getRequestURL();
        return url.toString();
    }
}
