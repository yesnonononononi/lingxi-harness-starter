package com.summit.runtime.loop;


import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.runtime.loop.*;
import com.summit.core.tool.ToolExecuteResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;


@Slf4j
public class DefaultLoopInterceptorProcessor implements LoopInterceptorProcessor {
    private final List<LoopInterceptor> interceptors;

    public DefaultLoopInterceptorProcessor(List<LoopInterceptor> interceptors) {
        Objects.requireNonNull(interceptors);
        this.interceptors = interceptors.stream().sorted(Comparator.comparingInt(LoopInterceptor::order)).toList();
    }

    @Override
    public InterceptorResult onLoopStart(LoopContext context) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onLoopStart(context);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor on loop start order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onBeforeModelInvoke(LoopContext context) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onBeforeModelInvoke(context);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor before the invocation of model order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onAfterModelInvoke(context, response);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor after the model invoke order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onAfterToolCall(context, results);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor after the tool call order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onBeforeToolCall(LoopContext context) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onBeforeToolCall(context);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor before the tool call order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onBeforeComplete(LoopContext context) {
        for (LoopInterceptor interceptor : interceptors) {
            try {
                InterceptorResult result = interceptor.onBeforeComplete(context);
                if (!result.shouldContinue()) return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor before completion order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }

    @Override
    public InterceptorResult onLoopEnd(LoopContext context) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onLoopEnd(context);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor on loop end order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }



    @Override
    public InterceptorResult onRunEnd(Execution execution) {
        InterceptorResult result;

        for (LoopInterceptor interceptor : interceptors) {
            try {
                result = interceptor.onRunEnd(execution);
                if (result.shouldContinue()) {
                    continue;
                }
                return result;
            } catch (Exception e) {
                if (interceptor.catchErr()) {
                    log.error("Error occurred while processing loop interceptor on run end order:{}", interceptor.order(), e);
                } else {
                    throw e;
                }
            }
        }
        return NONE;
    }










}
