package com.summit.tools.web;


import com.summit.harnesscore.tool.Tool;
import com.summit.harnesscore.tool.ToolExecutor;
import lombok.AllArgsConstructor;
import lombok.Getter;
@AllArgsConstructor
@Getter
public class WebSearchToolDefinition implements Tool {
    private final WebSearchEngine webSearchEngine;



    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String id() {
        return String.valueOf("web-search-tool".hashCode());
    }

    @Override
    public String description() {
        return "web search tool";
    }



    @Override
    public ToolExecutor executor() {
        return new WebSearchExecutor(this.webSearchEngine);
    }
}
