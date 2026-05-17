package com.jira.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    private String chatModel = "llama3.1";
    private String embeddingModel = "nomic-embed-text";
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 120_000;
}
