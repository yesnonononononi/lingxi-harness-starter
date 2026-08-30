package com.summit.tools.web;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.tools.arguments.WebSearchArguments;

import lombok.AllArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@AllArgsConstructor
public class WebSearchEngine {
    private final WebSearchConfig webSearchConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public String search(WebSearchArguments arguments) throws IOException, InterruptedException {
        String maxResults = arguments.getMaxResults();
        Integer maxRes = this.webSearchConfig.maxResult();

        if(maxRes != null && maxRes < Integer.parseInt(maxResults)) {
            arguments.setMaxResults(String.valueOf(maxRes));
        }
        return this.search(this.objectMapper.writeValueAsString(arguments));
    }
    public String search(String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .POST(
                        HttpRequest.BodyPublishers.ofString(json)
                )
                .header("Authorization", "Bearer " + this.webSearchConfig.apiKey())
                .uri(URI.create(this.webSearchConfig.baseUrl()))
                .timeout(Duration.ofSeconds(this.webSearchConfig.timeout()))
                .build();


        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

}
