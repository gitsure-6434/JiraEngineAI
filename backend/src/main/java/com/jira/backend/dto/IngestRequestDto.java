package com.jira.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IngestRequestDto {

    @NotBlank(message = "jql is required")
    private String jql;

    @Min(1)
    @Max(500)
    private int maxResults = 50;
}
