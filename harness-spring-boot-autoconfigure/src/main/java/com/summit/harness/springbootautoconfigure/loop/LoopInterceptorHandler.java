package com.summit.harness.springbootautoconfigure.loop;

import com.summit.core.runtime.loop.AgenticLoopListener;
import com.summit.runtime.loop.LoopInterceptorFactory;

import com.summit.core.runtime.loop.InterceptorResult;


import com.summit.core.runtime.loop.LoopContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;

@Component
@RequiredArgsConstructor
public class LoopInterceptorHandler implements ApplicationRunner {
    private final ApplicationContext applicationContext;
    private final LoopInterceptorFactory loopInterceptorFactory;

    @Override
    public void run(ApplicationArguments args) {
        String[] beanDefinitionNames = applicationContext.getBeanDefinitionNames();
        for (String beanDefinitionName : beanDefinitionNames) {

            Class<?> beanType = applicationContext.getType(beanDefinitionName);

            if (beanType == null ) continue;


            Class<?> userClass = ClassUtils.getUserClass(beanType);

            List<Method> hits = new ArrayList<>(Arrays.stream(ReflectionUtils.getAllDeclaredMethods(userClass))
                    .filter(m -> m.isAnnotationPresent(AgenticLoopListener.class))
                    .toList());

            for (Class<?> itf : ClassUtils.getAllInterfacesForClassAsSet(userClass)) {
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(AgenticLoopListener.class)) hits.add(m);
                }
            }

            if ((hits = resolveHits(hits)).isEmpty()) continue;

            Object bean = applicationContext.getBean(beanDefinitionName);

            for (Method method : hits) {
                AgenticLoopListener agenticLoopListener = method.getAnnotation(AgenticLoopListener.class);

                Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());

                validate(invocable, agenticLoopListener);

                loopInterceptorFactory.register(agenticLoopListener, loopContext -> (InterceptorResult) ReflectionUtils.invokeMethod(invocable, bean, loopContext));

            }
        }
    }


    private List<Method> resolveHits(List<Method> hits){
        Map<String, Method> unique = new LinkedHashMap<>();
        for (Method m : hits) {
            String key = m.getName() + Arrays.toString(m.getParameterTypes());
            unique.merge(key, m, (a, b) ->
                    a.getDeclaringClass().isAssignableFrom(b.getDeclaringClass()) ? b : a);
        }
        return new ArrayList<>(unique.values());
    }

    private static @NonNull String validate(Method invocable, AgenticLoopListener agenticLoopListener) {
        Class<?>[] parameterTypes = invocable.getParameterTypes();

        String identityDes = (agenticLoopListener.name() == null || agenticLoopListener.name().isEmpty()) ? agenticLoopListener.order() + "" : agenticLoopListener.name();

        if (parameterTypes.length != 1) throw new RuntimeException("Target method must be single param" + identityDes);

        if (!parameterTypes[0].isAssignableFrom(LoopContext.class))
            throw new RuntimeException("Target method param must be LoopContext " + identityDes);

        if (!InterceptorResult.class.isAssignableFrom(invocable.getReturnType())) {
            throw new IllegalStateException("... return must be InterceptorResult: " + identityDes);
        }
        return identityDes;
    }


}
