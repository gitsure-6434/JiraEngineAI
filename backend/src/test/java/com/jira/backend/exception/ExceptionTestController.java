package com.jira.backend.exception;

import com.jira.backend.dto.AnalyzeRequestDto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test")
public class ExceptionTestController {

    @PostMapping("/validate")
    void triggerValidation(@Valid @RequestBody AnalyzeRequestDto request) {
    }

    @PostMapping("/bad-request")
    void triggerBadRequest() {
        throw new BadRequestException("Invalid request payload");
    }
}
