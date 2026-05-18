package com.jira.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String publicBaseUrl = "";

    public boolean hasPublicBaseUrl() {
        return StringUtils.hasText(publicBaseUrl);
    }
}
