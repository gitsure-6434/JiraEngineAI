package com.jira.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class AnalyzeRequestDto {

    @NotBlank(message = "text is required")
    @Size(max = 10000, message = "text must be at most 10000 characters")
    private String text;

    private List<String> ticketIds = new ArrayList<>();

    private Integer topK;
}
