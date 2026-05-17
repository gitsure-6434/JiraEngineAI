package com.jira.backend.service;

import com.jira.backend.model.JiraIssueRecord;

import java.util.List;
import java.util.stream.Collectors;

public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildAnalysisPrompt(String userText, List<JiraIssueRecord> referencedTickets, String similarContext) {
        String referenced = referencedTickets.isEmpty()
                ? "None"
                : referencedTickets.stream()
                .map(JiraIssueRecord::toIndexableText)
                .collect(Collectors.joining("\n---\n"));

        return """
                You are a senior Java Spring Boot engineer performing Jira bug triage.
                Use the historical issues and user query below.

                Return ONLY valid JSON with this exact schema:
                {
                  "rootCauseSummary": "string",
                  "recommendedFix": "string",
                  "recommendedCodePatch": "string",
                  "reproductionSteps": ["step1", "step2"],
                  "relatedTicketIds": ["PROJ-1"]
                }

                User query:
                %s

                Referenced Jira tickets (live from Jira API):
                %s

                Similar historical issues (from vector DB):
                %s
                """.formatted(userText, referenced, similarContext);
    }

    public static String buildSimilarIssuesContext(List<String> issueTexts) {
        if (issueTexts.isEmpty()) {
            return "No similar historical issues found in vector database.";
        }
        return issueTexts.stream()
                .map(text -> "---\n" + text)
                .collect(Collectors.joining("\n"));
    }
}
