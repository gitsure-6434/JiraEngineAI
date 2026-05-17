package com.jira.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.config.OllamaProperties;
import com.jira.backend.exception.AiServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Component
public class OllamaClient implements OllamaPort {

    private final WebClient ollamaWebClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;

    public OllamaClient(
            @Qualifier("ollamaWebClient") WebClient ollamaWebClient,
            OllamaProperties properties,
            ObjectMapper objectMapper) {
        this.ollamaWebClient = ollamaWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Float> embed(String text) {
        try {
            JsonNode response = ollamaWebClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", properties.getEmbeddingModel(),
                            "prompt", text))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("embedding")) {
                throw new AiServiceException("Ollama returned an empty embedding response");
            }

            return objectMapper.convertValue(
                    response.get("embedding"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Float.class));
        } catch (WebClientResponseException ex) {
            throw new AiServiceException(
                    "Ollama embedding failed. Ensure model '%s' is pulled (ollama pull %s). Response: %s"
                            .formatted(properties.getEmbeddingModel(), properties.getEmbeddingModel(), ex.getResponseBodyAsString()),
                    ex);
        } catch (Exception ex) {
            throw new AiServiceException("Failed to connect to Ollama at " + properties.getBaseUrl(), ex);
        }
    }

    @Override
    public String chat(String prompt) {
        try {
            JsonNode response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", properties.getChatModel(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "stream", false))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.path("message").has("content")) {
                throw new AiServiceException("Ollama returned an empty chat response");
            }

            return response.path("message").path("content").asText();
        } catch (WebClientResponseException ex) {
            throw new AiServiceException(
                    "Ollama chat failed. Ensure model '%s' is pulled (ollama pull %s). Response: %s"
                            .formatted(properties.getChatModel(), properties.getChatModel(), ex.getResponseBodyAsString()),
                    ex);
        } catch (Exception ex) {
            if (ex instanceof AiServiceException aiServiceException) {
                throw aiServiceException;
            }
            throw new AiServiceException("Failed to connect to Ollama at " + properties.getBaseUrl(), ex);
        }
    }
}
