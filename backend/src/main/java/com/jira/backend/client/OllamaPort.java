package com.jira.backend.client;

import java.util.List;

public interface OllamaPort {

    List<Float> embed(String text);

    String chat(String prompt);
}
