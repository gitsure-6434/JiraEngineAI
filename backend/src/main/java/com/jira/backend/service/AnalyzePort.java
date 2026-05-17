package com.jira.backend.service;

import com.jira.backend.dto.AnalyzeRequestDto;
import com.jira.backend.dto.AnalyzeResponseDto;

public interface AnalyzePort {

    AnalyzeResponseDto analyze(AnalyzeRequestDto request);
}
