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
    /** Preferred minimum cosine similarity (0–1) for multiple matches. */
    private double issueSimilarityThreshold = 0.55;
    /** When nothing meets the threshold, still return the best match if score is at least this. */
    private double issueSimilarityFallbackMin = 0.40;
    private int topKDefault = 5;
}
