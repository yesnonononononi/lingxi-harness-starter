package com.summit.tools.arguments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchArguments {
    private String query;
    private String maxResults;
    private String startDate;
    private String endDate;
}
