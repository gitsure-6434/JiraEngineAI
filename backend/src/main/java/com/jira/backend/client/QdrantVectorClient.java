package com.jira.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.backend.config.QdrantProperties;
import com.jira.backend.exception.AiServiceException;
import com.jira.backend.model.VectorSearchResult;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

    public List<VectorSearchResult> searchIssues(List<Float> vector, int topK, double minScore) {
        ensureReady();
        return search(properties.getIssuesCollection(), vector, topK, minScore);
    }

    public List<VectorSearchResult> searchCache(List<Float> vector, int topK) {
        ensureReady();
        return search(properties.getSearchCacheCollection(), vector, topK, 0.0);
    }

    public long countIssues() {
        ensureReady();
        return countPoints(properties.getIssuesCollection());
    }

    public List<VectorSearchResult> scrollAllIssues(int limit) {
        ensureReady();
        return scrollPoints(properties.getIssuesCollection(), limit, null);
    }

    public List<VectorSearchResult> scrollAllSearchCache() {
        ensureReady();
        return scrollAllPages(properties.getSearchCacheCollection(), 100);
    }

    public void deleteSearchCachePoints(List<String> pointIds) {
        ensureReady();
        deletePoints(properties.getSearchCacheCollection(), pointIds);
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

    private List<VectorSearchResult> search(String collection, List<Float> vector, int topK, double minScore) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", topK);
        body.put("with_payload", true);
        if (minScore > 0.0) {
            body.put("score_threshold", minScore);
        }

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

    private long countPoints(String collection) {
        try {
            JsonNode response = qdrantWebClient.get()
                    .uri("/collections/{name}", collection)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (response != null && response.path("result").has("points_count")) {
                return response.path("result").path("points_count").asLong(0);
            }
        } catch (Exception ex) {
            log.warn("Could not read Qdrant collection stats for {}: {}", collection, ex.getMessage());
        }
        return 0;
    }

    private List<VectorSearchResult> scrollAllPages(String collection, int pageSize) {
        List<VectorSearchResult> all = new ArrayList<>();
        Object offset = null;

        while (true) {
            JsonNode response = scrollRaw(collection, pageSize, offset);
            List<VectorSearchResult> page = mapScrollPoints(response);
            all.addAll(page);

            if (response == null || !response.path("result").has("next_page_offset")
                    || response.path("result").get("next_page_offset").isNull()) {
                break;
            }
            offset = objectMapper.convertValue(response.path("result").get("next_page_offset"), Object.class);
            if (page.isEmpty()) {
                break;
            }
        }

        return all;
    }

    private JsonNode scrollRaw(String collection, int limit, Object offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        if (offset != null) {
            body.put("offset", offset);
        }

        try {
            return qdrantWebClient.post()
                    .uri("/collections/{name}/points/scroll", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception ex) {
            throw new AiServiceException("Failed to scroll Qdrant collection: " + collection, ex);
        }
    }

    private List<VectorSearchResult> scrollPoints(String collection, int limit, Object offset) {
        try {
            return mapScrollPoints(scrollRaw(collection, limit, offset));
        } catch (Exception ex) {
            throw new AiServiceException("Failed to scroll Qdrant collection: " + collection, ex);
        }
    }

    private List<VectorSearchResult> mapScrollPoints(JsonNode response) {
        if (response == null || !response.path("result").has("points")) {
            return List.of();
        }

        List<VectorSearchResult> results = new ArrayList<>();
        for (JsonNode item : response.path("result").path("points")) {
            String pointId = item.has("id") ? item.get("id").asText() : UUID.randomUUID().toString();
            Map<String, Object> payload = objectMapper.convertValue(
                    item.path("payload"),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            results.add(VectorSearchResult.builder()
                    .pointId(pointId)
                    .score(0.0)
                    .payload(payload)
                    .build());
        }
        return results;
    }

    private void deletePoints(String collection, List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }

        Map<String, Object> body = Map.of("points", pointIds);
        try {
            qdrantWebClient.post()
                    .uri("/collections/{name}/points/delete", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception ex) {
            throw new AiServiceException("Failed to delete points from collection: " + collection, ex);
        }
    }
}
