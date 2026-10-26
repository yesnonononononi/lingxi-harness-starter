package com.summit.harness.springbootautoconfigure.toolHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.anno.OtherProperty;
import com.summit.core.anno.Tool;

import com.summit.core.anno.ToolProperty;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;



@RequiredArgsConstructor
@Component
public class ToolRegister implements ApplicationRunner {
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) continue;

            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);

            Tool toolAnno = targetClass.getAnnotation(Tool.class);

            if (toolAnno != null) {
                // Explicit definitions must win before resolving annotation-only executor dependencies.
                if (toolRegistry.getToolById(toolAnno.id()) != null) {
                    continue;
                }
                try {
                    ToolExecutor executor = bean instanceof ToolExecutor candidate
                            && toolAnno.executor().isAssignableFrom(targetClass)
                            ? candidate : applicationContext.getBean(toolAnno.executor());
                    ToolDefinition<?> definition = buildToolDefinition(toolAnno, executor);
                    toolRegistry.register(definition);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to register tool: " + toolAnno.id(), e);
                }
            }
        }
    }



    private ToolDefinition<?> buildToolDefinition(Tool tool, ToolExecutor executor) {
        ObjectNode objectNode = objectMapper.createObjectNode();

        ObjectNode propertiesNode  = objectMapper.createObjectNode();
        ArrayNode requiredArrayNode = objectMapper.createArrayNode();

        for (ToolProperty prop : tool.props()) {

            ObjectNode argvNode = objectMapper.createObjectNode();

            argvNode.put("type",prop.value().type());
            argvNode.put("description",prop.value().description());

            if(prop.value().isRequired()){
                requiredArrayNode.add(prop.key());
            }

            propertiesNode.set(prop.key(),argvNode);
        }

        objectNode.put("type",tool.type());
        objectNode.set("properties",propertiesNode);
        objectNode.set("required", requiredArrayNode);

        for (OtherProperty other : tool.others()) {
            objectNode.put(other.key(),other.value());
        }

        return ToolDefinition.builder()
                .id(tool.id())
                .name(tool.name())
                .executor(executor)
                .timeout(tool.timeout())
                .concurrentPolicy(tool.concurrentPolicy())
                .description(tool.description())
                .parametersJsonSchema(objectNode.toString())
                .maxOutput(tool.maxOutput())
                .build();
    }


}
