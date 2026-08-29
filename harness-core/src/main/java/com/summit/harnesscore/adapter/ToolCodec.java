package com.summit.harnesscore.adapter;

import com.summit.harnesscore.tool.ToolDefinition;

/**
 * Tool encoding and decoding SPI. The tool input parameter in core is a neutral JSON-Schema string.
 * Convert from the implementation side to the tool specification type on the framework side.
 *
 * @param <T> Tool specification types for specific model frameworks (such as the ToolSpecification for langchain4j)
 */
public interface ToolCodec<T> {

    /**
     * core tool definition -> framework tool specification.
     */
    T toFrameworkTool(ToolDefinition<?> toolDefinition);
}
