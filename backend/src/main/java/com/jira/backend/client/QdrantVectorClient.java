package com.jira.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.exception.AiServiceException;
import com.jira.backend.model.VectorSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QdrantVectorClient {

    private final WebClient qdrantWebClient;
    private final QdrantProperties properties;
    private final ObjectMapper objectMapper;

    public QdrantVectorClient(
            @Qualifier("qdrantWebClient") WebClient qdrantWebClient,
            QdrantProperties properties,
            ObjectMapper objectMapper) {
        this.qdrantWebClient = qdrantWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private volatile boolean initialized;

    public void upsertIssue(String ticketId, List<Float> vector, Map<String, Object> payload) {
        ensureReady();
        upsertPoint(properties.getIssuesCollection(), ticketId, vector, payload);
    }

    public void upsertSearchCache(String cacheKey, List<Float> vector, Map<String, Object> payload) {
        ensureReady();
        upsertPoint(properties.getSearchCacheCollection(), cacheKey, vector, payload);
    }

    public List<VectorSearchResult> searchIssues(List<Float> vector, int topK) {
        ensureReady();
        return search(properties.getIssuesCollection(), vector, topK);
    }

    public List<VectorSearchResult> searchCache(List<Float> vector, int topK) {
        ensureReady();
        return search(properties.getSearchCacheCollection(), vector, topK);
    }

    private void ensureReady() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    ensureCollection(properties.getIssuesCollection());
                    ensureCollection(properties.getSearchCacheCollection());
                    initialized = true;
                }
            }
        }
    }

    private void ensureCollection(String collectionName) {
        try {
            qdrantWebClient.get()
                    .uri("/collections/{name}", collectionName)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException.NotFound notFound) {
            createCollection(collectionName);
        } catch (Exception ex) {
            throw new AiServiceException("Failed to reach Qdrant at " + properties.getBaseUrl(), ex);
        }
    }

    private void createCollection(String collectionName) {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", properties.getVectorSize(),
                        "distance", "Cosine"));

        try {
            qdrantWebClient.put()
                    .uri("/collections/{name}", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception ex) {
            throw new AiServiceException("Failed to create Qdrant collection: " + collectionName, ex);
        }
    }

    private void upsertPoint(String collection, String pointId, List<Float> vector, Map<String, Object> payload) {
        Map<String, Object> point = Map.of(
                "id", pointId,
                "vector", vector,
                "payload", payload);

        Map<String, Object> body = Map.of("points", List.of(point));

        try {
            qdrantWebClient.put()
                    .uri("/collections/{name}/points", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception ex) {
            throw new AiServiceException("Failed to upsert vector for point: " + pointId, ex);
        }
    }

    private List<VectorSearchResult> search(String collection, List<Float> vector, int topK) {
        Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", topK,
                "with_payload", true);

        try {
            JsonNode response = qdrantWebClient.post()
                    .uri("/collections/{name}/points/search", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("result")) {
                return List.of();
            }

            List<VectorSearchResult> results = new ArrayList<>();
            for (JsonNode item : response.get("result")) {
                String pointId = item.has("id") ? item.get("id").asText() : UUID.randomUUID().toString();
                double score = item.path("score").asDouble(0.0);
                Map<String, Object> payload = objectMapper.convertValue(
                        item.path("payload"),
                        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                results.add(VectorSearchResult.builder()
                        .pointId(pointId)
                        .score(score)
                        .payload(payload)
                        .build());
            }
            return results;
        } catch (Exception ex) {
            throw new AiServiceException("Vector search failed in collection: " + collection, ex);
        }
    }
}
