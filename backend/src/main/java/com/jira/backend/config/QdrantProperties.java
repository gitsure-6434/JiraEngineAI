package com.jira.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

    private String baseUrl = "http://localhost:6333";
    private String issuesCollection = "jira_issues";
    private String searchCacheCollection = "search_cache";
    private int vectorSize = 768;
    private double cacheSimilarityThreshold = 0.92;
    private int topKDefault = 5;
}
