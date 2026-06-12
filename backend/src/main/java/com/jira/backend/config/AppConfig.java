package com.jira.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableAsync
@EnableConfigurationProperties({
        AppProperties.class,
        JiraProperties.class,
        OllamaProperties.class,
        QdrantProperties.class})
public class AppConfig {

    @Bean(name = "jiraWebhookExecutor")
    ThreadPoolTaskExecutor jiraWebhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("jira-webhook-");
        executor.initialize();
        return executor;
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean("ollamaWebClient")
    WebClient ollamaWebClient(OllamaProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean("qdrantWebClient")
    WebClient qdrantWebClient(QdrantProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Bean("jiraRestTemplate")
    RestTemplate jiraRestTemplate(JiraProperties properties) {
        return new RestTemplateBuilder()
                .rootUri(normalizeBaseUrl(properties.getBaseUrl()))
                .build();
    }

        //normalizing base url
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost";
        } else 
        
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
