package com.summit.harness.springbootautoconfigure.loop;

import com.summit.core.runtime.loop.AgenticLoopListener;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.InterceptorType;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.loop.LoopInterceptorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LoopInterceptorHandlerTest {
    @Test
    void registersManagedBeanAndPassesTheOriginalContext() {
        try (AnnotationConfigApplicationContext context = context(Listener.class)) {
            LoopInterceptorFactory factory = scan(context);
            Listener listener = context.getBean(Listener.class);
            LoopContext payload = new LoopContext(LoopMessages.builder().build(), null, 3, null);

            assertEquals(0, listener.calls);
            assertSame(InterceptorResult.NONE, processor(factory).onLoopStart(payload));
            assertEquals(1, listener.calls);
            assertSame(payload, listener.received);
        }
    }

    @Test
    void inheritedListenerRunsOnTheManagedSubclass() {
        try (AnnotationConfigApplicationContext context = context(InheritedListener.class)) {
            processor(scan(context)).onLoopStart(null);

            assertEquals(1, context.getBean(InheritedListener.class).calls);
        }
    }

    @Test
    void interfaceAnnotationIsDiscoveredWithoutAnImplementationAnnotation() {
        try (AnnotationConfigApplicationContext context = context(InterfaceListener.class)) {
            LoopInterceptorFactory factory = scan(context);
            processor(factory).onLoopStart(null);

            assertEquals(1, context.getBean(InterfaceListener.class).calls);
            assertTrue(factory.get().containsKey("interface"));
        }
    }

    @Test
    void interfaceExposedByBeanMethodDiscoversAnnotationAndInvokesTheImplementationOverride() {
        try (AnnotationConfigApplicationContext context = context(InterfaceExposure.class)) {
            assertSame(ListenerContract.class, context.getType("interfaceExposedListener"));
            assertFalse(context.getBeanFactory().containsSingleton("interfaceExposedListener"));

            LoopInterceptorFactory factory = scan(context);
            assertTrue(factory.get().containsKey("interface"));
            processor(factory).onLoopStart(null);

            InterfaceListener target = (InterfaceListener) context.getBean("interfaceExposedListener");
            assertEquals(1, target.calls);
        }
    }

    @Test
    void interfaceExposedByBeanMethodDoesNotDiscoverImplementationOnlyAnnotations() {
        try (AnnotationConfigApplicationContext context = context(ImplementationOnlyExposure.class)) {
            assertSame(UnannotatedContract.class, context.getType("implementationOnlyListener"));
            assertFalse(context.getBeanFactory().containsSingleton("implementationOnlyListener"));

            LoopInterceptorFactory factory = scan(context);
            assertTrue(factory.get().isEmpty());
            assertFalse(context.getBeanFactory().containsSingleton("implementationOnlyListener"));
        }
    }

    @Test
    void implementationAnnotationOverridesTheInterfaceWithoutDuplicateRegistration() {
        try (AnnotationConfigApplicationContext context = context(OverridingListener.class)) {
            LoopInterceptorFactory factory = scan(context);
            processor(factory).onLoopStart(null);

            assertEquals(1, factory.get().size());
            assertTrue(factory.get().containsKey("implementation"));
            assertEquals(1, context.getBean(OverridingListener.class).calls);
        }
    }

    @Test
    void cglibProxyRetainsAdviceAndInvokesTheManagedTarget() {
        Listener target = new Listener();
        AtomicInteger adviceCalls = new AtomicInteger();
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            adviceCalls.incrementAndGet();
            return invocation.proceed();
        });
        try (AnnotationConfigApplicationContext context = context()) {
            Object proxy = proxyFactory.getProxy();
            context.registerBean("listenerProxy", Object.class, () -> proxy);
            assertTrue(AopUtils.isCglibProxy(context.getBean("listenerProxy")));
            processor(scan(context)).onLoopStart(null);

            assertEquals(1, target.calls);
            assertEquals(1, adviceCalls.get());
        }
    }

    @Test
    void jdkProxyDiscoversInterfaceAnnotationsAndRetainsAdvice() {
        InterfaceListener target = new InterfaceListener();
        AtomicInteger adviceCalls = new AtomicInteger();
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ListenerContract.class);
        proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            adviceCalls.incrementAndGet();
            return invocation.proceed();
        });
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("listenerProxy", Object.class, () -> proxyFactory.getProxy());
            assertTrue(AopUtils.isJdkDynamicProxy(context.getBean("listenerProxy")));
            processor(scan(context)).onLoopStart(null);

            assertEquals(1, target.calls);
            assertEquals(1, adviceCalls.get());
        }
    }

    @Test
    void jdkProxyDoesNotDiscoverAnnotationsHiddenByItsExposedInterface() {
        TargetAnnotatedListener target = new TargetAnnotatedListener();
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(UnannotatedContract.class);
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean("listenerProxy", Object.class, () -> proxyFactory.getProxy());
            assertTrue(AopUtils.isJdkDynamicProxy(context.getBean("listenerProxy")));
            LoopInterceptorFactory factory = scan(context);

            assertFalse(factory.get().containsKey("target-annotation"));
            processor(factory).onLoopStart(null);
            assertEquals(0, target.calls);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidListeners")
    void rejectsInvalidSignaturesBeforeRegisteringThem(Class<?> listenerClass) {
        try (AnnotationConfigApplicationContext context = context(listenerClass)) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> scan(context));

            assertTrue(failure.getMessage().contains("invalid"));
            assertTrue(context.getBean(LoopInterceptorFactory.class).get().isEmpty());
        }
    }

    static Stream<Class<?>> invalidListeners() {
        return Stream.of(NoParameters.class, TooManyParameters.class, WrongParameter.class, WrongReturn.class);
    }

    @Test
    void duplicateNamesFailWithoutOverwritingTheExistingListener() {
        try (AnnotationConfigApplicationContext context = context(Listener.class, DuplicateListener.class)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> scan(context));

            assertTrue(failure.getMessage().contains("listener"));
            assertEquals(1, context.getBean(LoopInterceptorFactory.class).get().size());
        }
    }

    @Test
    void skipsUnannotatedLazyBeansWithoutInstantiatingThem() {
        AtomicInteger constructed = new AtomicInteger();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("lazyUnrelated", Unrelated.class, () -> {
                constructed.incrementAndGet();
                return new Unrelated();
            }, definition -> definition.setLazyInit(true));
            registerInfrastructure(context);
            context.refresh();

            assertTrue(scan(context).get().isEmpty());
            assertEquals(0, constructed.get());
        }
    }

    @Test
    void skipsUnknownFactoryBeanProductTypes() {
        try (AnnotationConfigApplicationContext context = context(Listener.class)) {
            context.registerBean("unknownProduct", UnknownFactory.class);

            assertNull(context.getType("unknownProduct"));
            assertEquals(1, scan(context).get().size());
        }
    }

    @Test
    void callbackFailurePreservesTheOriginalException() {
        try (AnnotationConfigApplicationContext context = context(FailingListener.class)) {
            FailingListener listener = context.getBean(FailingListener.class);

            assertSame(listener.failure, assertThrows(IllegalStateException.class,
                    () -> processor(scan(context)).onLoopStart(null)));
        }
    }

    private AnnotationConfigApplicationContext context(Class<?>... listeners) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (listeners.length > 0) context.register(listeners);
        registerInfrastructure(context);
        context.refresh();
        return context;
    }

    private void registerInfrastructure(AnnotationConfigApplicationContext context) {
        context.registerBean(LoopInterceptorFactory.class,
                () -> new LoopInterceptorFactory(Map.copyOf(context.getBeansOfType(LoopInterceptor.class))));
        context.register(LoopInterceptorHandler.class);
    }

    private LoopInterceptorFactory scan(AnnotationConfigApplicationContext context) {
        context.getBean(LoopInterceptorHandler.class).run(new DefaultApplicationArguments());
        return context.getBean(LoopInterceptorFactory.class);
    }

    private DefaultLoopInterceptorProcessor processor(LoopInterceptorFactory factory) {
        return new DefaultLoopInterceptorProcessor(factory);
    }

    public static class Listener {
        int calls;
        LoopContext received;
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "listener", order = -400)
        public InterceptorResult listen(LoopContext context) {
            calls++;
            received = context;
            return InterceptorResult.NONE;
        }
    }

    public static class InheritedListener extends Listener { }

    public interface ListenerContract {
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "interface", order = -400)
        InterceptorResult listen(LoopContext context);
    }

    public static class InterfaceListener implements ListenerContract {
        int calls;
        @Override
        public InterceptorResult listen(LoopContext context) {
            calls++;
            return InterceptorResult.NONE;
        }
    }

    public static class OverridingListener implements ListenerContract {
        int calls;
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "implementation", order = -300)
        public InterceptorResult listen(LoopContext context) {
            calls++;
            return InterceptorResult.NONE;
        }
    }

    public interface UnannotatedContract {
        InterceptorResult listen(LoopContext context);
    }

    @Configuration(proxyBeanMethods = false)
    public static class InterfaceExposure {
        @Bean
        @Lazy
        ListenerContract interfaceExposedListener() { return new InterfaceListener(); }
    }

    @Configuration(proxyBeanMethods = false)
    public static class ImplementationOnlyExposure {
        @Bean
        @Lazy
        UnannotatedContract implementationOnlyListener() { return new TargetAnnotatedListener(); }
    }

    public static class TargetAnnotatedListener implements UnannotatedContract {
        int calls;
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "target-annotation", order = -300)
        public InterceptorResult listen(LoopContext context) {
            calls++;
            return InterceptorResult.NONE;
        }
    }

    public static class NoParameters {
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "invalid", order = 0)
        public InterceptorResult listen() { return InterceptorResult.NONE; }
    }

    public static class TooManyParameters {
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "invalid", order = 0)
        public InterceptorResult listen(LoopContext first, LoopContext second) { return InterceptorResult.NONE; }
    }

    public static class WrongParameter {
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "invalid", order = 0)
        public InterceptorResult listen(String context) { return InterceptorResult.NONE; }
    }

    public static class WrongReturn {
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "invalid", order = 0)
        public Object listen(LoopContext context) { return InterceptorResult.NONE; }
    }

    public static class DuplicateListener {
        @AgenticLoopListener(value = InterceptorType.LOOP_END, name = "listener", order = -200)
        public InterceptorResult listen(LoopContext context) { return InterceptorResult.NONE; }
    }

    public static class Unrelated { }

    public static class UnknownFactory implements FactoryBean<Object> {
        public Object getObject() { return new Object(); }
        public Class<?> getObjectType() { return null; }
    }

    public static class FailingListener {
        final IllegalStateException failure = new IllegalStateException("listener failure");
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "failure", order = -400)
        public InterceptorResult listen(LoopContext context) { throw failure; }
    }
}
