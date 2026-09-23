package com.summit.harnessexample.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** Business-side workflow of this example, built exclusively on the framework's generic SPI. */
@Configuration
public class ExampleWorkflowConfiguration {

    /** The example interprets this tool's promised result as a pending user choice. */
    @Bean
    public ToolDefinition<ToolExecutor> requireChoiceTool(ObjectMapper mapper) {
        ToolExecutor executor = execution -> {
            try {
                var json = mapper.readTree(execution.getArgs());
                String question = json.path("question").asText();

                List<String> choices = mapper.convertValue(json.path("choice"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                return ToolExecuteResult.promise(mapper.writeValueAsString(Map.of(
                        "status", "PENDING",
                        "question", question,
                        "choices", choices,
                        "allowCustomInput", true)));
            } catch (Exception e) {
                return ToolExecuteResult.err(e.getMessage());
            }
        };
        return ToolDefinition.<ToolExecutor>builder()
                .id("require_choice").name("require_choice").executor(executor).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .description("""
                        Ask the user a question and wait for their explicit answer before continuing.
                        Call it BEFORE doing the work whenever the request leaves a decision open that
                        only the user can make — technology or library choice, scope, priority,
                        naming, data loss or other irreversible effects — instead of guessing.
                        Provide 1-6 short suggested options covering the plausible answers; the user
                        may also reply with custom text of their own, so the options are a
                        recommendation rather than a whitelist.
                        Do not use it for anything you can find out yourself with the available tools,
                        and do not ask more than one question at a time.
                        This call records the question and suspends the current execution. A later
                        request may add the selected answer to the conversation before resuming.""")
                .parametersJsonSchema("""
                        {"type":"object","properties":{"question":{"type":"string"},
                        "choice":{"type":"array","items":{"type":"string"}}},
                        "required":["question","choice"]}
                        """).maxOutput(1_000).timeout(0L).build();
    }

}
