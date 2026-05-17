package com.jira.backend.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class VectorSearchResult {

    private final String pointId;
    private final double score;
    private final Map<String, Object> payload;
}
