package com.summit.tools.web;

import lombok.Builder;


@Builder
public record WebSearchConfig (String baseUrl, String apiKey,Long timeout){

}
