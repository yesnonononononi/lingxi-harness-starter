package com.summit.harness.springbootautoconfigure.toolHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.anno.Tool;
import com.summit.core.tool.AnnotationToolDefinitionFactory;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;



@RequiredArgsConstructor
@Component
@Order(-1000)
public class ToolRegister implements ApplicationRunner {
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        AnnotationToolDefinitionFactory definitionFactory = new AnnotationToolDefinitionFactory(objectMapper);
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
                    ToolDefinition<?> definition = definitionFactory.create(toolAnno, executor);
                    toolRegistry.register(definition);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to register tool: " + toolAnno.id(), e);
                }
            }
        }
    }



}
