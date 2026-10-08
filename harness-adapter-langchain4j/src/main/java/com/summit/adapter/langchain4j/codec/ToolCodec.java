package com.summit.adapter.langchain4j.codec;

import com.summit.core.tool.ToolDefinition;

/**
 * Converts a harness tool definition into a LangChain4j tool specification.
 *
 * @param <T> LangChain4j tool specification type
 */
public interface ToolCodec<T> {

    T toFrameworkTool(ToolDefinition<?> toolDefinition);
}
