package com.jira.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    private String baseUrl;
    private String email;
    private String apiToken;
    private String syncProjectKey = "SCRUM";
    private String webhookSecret = "";

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && email != null && !email.isBlank()
                && apiToken != null && !apiToken.isBlank()
                && !apiToken.startsWith("JIRA_API_TOKEN=");
    }
}
