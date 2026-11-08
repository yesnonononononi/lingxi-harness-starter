package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.compact.Tokenizer;
import com.summit.core.runtime.loop.AgenticLoopListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.InterceptorType;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopInterceptorProcessor;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.harness.springbootautoconfigure.config.execution.ExecutionRuntimeConfig;
import com.summit.harness.springbootautoconfigure.loop.LoopInterceptorHandler;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.loop.LoopInterceptorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoopListenerAutoConfigurationTest {
    @Test
    void autoConfigurationPublishesTheFactoryAndScannerWithoutPackageScanning() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.refresh();

            assertEquals(1, context.getBeansOfType(LoopInterceptorFactory.class).size());
            assertEquals(1, context.getBeansOfType(LoopInterceptorHandler.class).size());
            assertSame(context.getBean(LoopInterceptorHandler.class), context.getBean(ApplicationRunner.class));
            assertSame(context.getBean(LoopInterceptor.class),
                    context.getBean(LoopInterceptorFactory.class).get().get("loopInterceptor"));
        }
    }

    @Test
    void applicationFactoryReplacesTheDefaultWithoutLosingTheScanner() {
        LoopInterceptorFactory custom = new LoopInterceptorFactory(Map.of());
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("customFactory", LoopInterceptorFactory.class, () -> custom);
            context.refresh();

            assertSame(custom, context.getBean(LoopInterceptorFactory.class));
            assertEquals(1, context.getBeansOfType(LoopInterceptorFactory.class).size());
            assertNotNull(context.getBean(LoopInterceptorHandler.class));
        }
    }

    @Test
    void starterScansAnnotationsAndPreservesExistingInterceptorBeans() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.register(Listener.class);
            context.refresh();
            runScanners(context);

            LoopInterceptorFactory factory = context.getBean(LoopInterceptorFactory.class);
            assertTrue(factory.get().containsKey("annotated"));
            assertTrue(factory.get().containsKey("loopInterceptor"));
            new DefaultLoopInterceptorProcessor(factory).onLoopStart(payload());
            assertEquals(1, context.getBean(Listener.class).calls);
        }
    }

    @Test
    void annotationsMustReachTheProcessorCreatedBeforeApplicationRunnersExecute() {
        List<String> seen = new ArrayList<>();
        try (AnnotationConfigApplicationContext context = context()) {
            context.register(Listener.class);
            context.registerBean("ordinaryInterceptor", LoopInterceptor.class, () -> new LoopInterceptor() {
                public int order() { return -500; }
                public InterceptorResult onLoopStart(LoopContext payload) {
                    seen.add("ordinary");
                    return InterceptorResult.NONE;
                }
            });
            context.registerBean(LoopInterceptorProcessor.class,
                    () -> new ExecutionRuntimeConfig().defaultLoopInterceptorProcessor(
                            context.getBean(LoopInterceptorFactory.class)));
            context.refresh();
            LoopInterceptorProcessor processor = context.getBean(LoopInterceptorProcessor.class);
            runScanners(context);

            assertTrue(context.getBean(LoopInterceptorFactory.class).get().containsKey("annotated"));
            processor.onLoopStart(payload());

            assertEquals(List.of("ordinary"), seen);
            assertEquals(1, context.getBean(Listener.class).calls,
                    "A listener registered by the startup runner must be invoked by the actual runtime processor");
        }
    }

    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(Tokenizer.class, () -> (Tokenizer) Proxy.newProxyInstance(
                Tokenizer.class.getClassLoader(), new Class<?>[]{Tokenizer.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException("Tokenizer is unused"); }));
        context.register(InterceptorConfig.class);
        return context;
    }

    private void runScanners(AnnotationConfigApplicationContext context) {
        context.getBean(LoopInterceptorHandler.class).run(new DefaultApplicationArguments());
    }

    private LoopContext payload() {
        return LoopContext.builder().loopMessages(LoopMessages.builder().build())
                .signal(new ExecutionControlSignal("annotation-test")).consecutiveCompactTurns(() -> 0).build();
    }

    public static class Listener {
        int calls;
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "annotated", order = -300)
        public InterceptorResult listen(LoopContext context) {
            calls++;
            return InterceptorResult.NONE;
        }
    }
}
