package com.summit.runtime.loop;

import com.summit.core.runtime.loop.AgenticLoopListener;
import com.summit.core.runtime.loop.InterceptorType;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.tool.ToolExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j

public class LoopInterceptorFactory {
    private final ConcurrentHashMap<String, LoopInterceptor> interceptors = new ConcurrentHashMap<>();

    public LoopInterceptorFactory(Map<String, LoopInterceptor> beans) {
        beans.forEach((beanName, interceptor) ->
                interceptors.put(orDefault(beanName, interceptor.order()), interceptor));
    }

    public LoopInterceptor register(AgenticLoopListener agenticLoopListener, Function<LoopContext, InterceptorResult> method) {
        Objects.requireNonNull(method,"The target method cannot be null");


        LoopInterceptor loopInterceptor = new LoopInterceptor() {
            @Override
            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                if (InterceptorType.BEFORE_INVOKE.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onBeforeModelInvoke(context);
            }

            @Override
            public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
                if (InterceptorType.AFTER_INVOKE.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onAfterModelInvoke(context, response);
            }

            @Override
            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                if (InterceptorType.AFTER_TOOL_CALL.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onAfterToolCall(context, results);
            }

            @Override
            public InterceptorResult onBeforeToolCall(LoopContext context) {
                if (InterceptorType.BEFORE_TOOL_CALL.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onBeforeToolCall(context);
            }

            @Override
            public InterceptorResult onBeforeComplete(LoopContext context) {
                if (InterceptorType.BEFORE_COMPLETE.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onBeforeComplete(context);
            }

            @Override
            public InterceptorResult onLoopEnd(LoopContext context) {
                if (InterceptorType.LOOP_END.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onLoopEnd(context);
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                if (InterceptorType.LOOP_START.equals(agenticLoopListener.value())) {
                    return method.apply(context);
                }
                return LoopInterceptor.super.onLoopStart(context);
            }

            @Override
            public InterceptorResult onRunEnd(Execution execution) {
                if (InterceptorType.RUN_END.equals(agenticLoopListener.value())) {
                    return method.apply(
                             LoopContext.builder()
                                     .loopMessages(LoopMessages.builder().execution(execution).build())
                                     .build()
                    );
                }
                return LoopInterceptor.super.onRunEnd(execution);
            }

            @Override
            public int order() {
                return agenticLoopListener.order();
            }

            @Override
            public boolean catchErr() {
                return agenticLoopListener.catchErr();
            }
        };

        this.register(agenticLoopListener.name(),loopInterceptor);
        return loopInterceptor;
    }
    public void register(String name, LoopInterceptor loopInterceptor) {
        String key = orDefault(name, loopInterceptor.order());
        if (interceptors.putIfAbsent(key, loopInterceptor) != null) {
            throw new IllegalStateException("Duplicated name " + key);
        }
    }

    public void register(LoopInterceptor loopInterceptor){
        register(null,loopInterceptor);
    }

    public LoopInterceptor unRegister(String name, Integer order) {
        Map<String, LoopInterceptor> result = extractResultList(name, order);

        if (result != null && !result.isEmpty()) {
            if (result.size() > 1) {
                throw new RuntimeException("Found lots of elements which meets your condition" + result);
            }
        } else {
            return null;
        }

        result.forEach(this.interceptors::remove);

        return  new ArrayList<>(result.values()).getFirst();
    }

    private @Nullable Map<String, LoopInterceptor> extractResultList(String name, Integer order) {
        Map<String,LoopInterceptor> result = null;

        for (Map.Entry<String, LoopInterceptor> interceptorEntry : this.interceptors.entrySet()) {
            String interceptorName = interceptorEntry.getKey();
            LoopInterceptor loopInterceptor = interceptorEntry.getValue();

            if ((name != null && !name.isEmpty() && name.equals(interceptorName)) || (order != null && loopInterceptor.order() == order)) {
                result = Objects.requireNonNullElse(result,new HashMap<>());
                result.put(interceptorName,loopInterceptor);
            }
        }
        return result;
    }

    public Map<String, LoopInterceptor> get() {
        return interceptors.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue().order()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public List<LoopInterceptor> getInterceptors() {
       return this.interceptors.values().stream()
                .sorted(Comparator.comparingInt(LoopInterceptor::order))
                .toList();
    }

    private String orDefault(String name, int order){
        if(name != null && !name.isEmpty())return name;
        return "LoopInterceptor-"+UUID.randomUUID()+"-"+order;
    }

}