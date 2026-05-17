package com.jira.backend.controller;

import com.jira.backend.dto.AnalyzeRequestDto;
import com.jira.backend.dto.AnalyzeResponseDto;
import com.jira.backend.service.AnalyzePort;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyze")
@RequiredArgsConstructor
public class AnalyzeController {

    private final AnalyzePort analyzePort;

    @PostMapping
    public AnalyzeResponseDto analyze(@Valid @RequestBody AnalyzeRequestDto request) {
        return analyzePort.analyze(request);
    }
}
