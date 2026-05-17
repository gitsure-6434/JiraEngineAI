package com.jira.backend.controller;

import com.jira.backend.dto.IngestRequestDto;
import com.jira.backend.dto.IngestResponseDto;
import com.jira.backend.service.IngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestResponseDto ingest(@Valid @RequestBody IngestRequestDto request) {
        return ingestService.ingestFromJira(request);
    }
}
