package ru.a1pha1337.featurify.client.toggle;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.MethodClassKey;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Infrastructure advisor; feature values remain exclusively in the existing service caches. */
public final class FeatureToggleAdvisor extends StaticMethodMatcherPointcutAdvisor {
    private final ConcurrentMap<MethodClassKey, Optional<FeatureToggleCondition>> conditions = new ConcurrentHashMap<>();

    public FeatureToggleAdvisor(BeanFactory beans) {
        // Run before the default transaction advisor, leaving room for authorization advice.
        setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        setAdvice((MethodInterceptor) invocation -> {
            FeatureToggleCondition condition = condition(invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
            return condition == null || condition.matches(beans) ? invocation.proceed() : null;
        });
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return condition(method, targetClass) != null;
    }

    private FeatureToggleCondition condition(Method method, Class<?> targetClass) {
        return conditions.computeIfAbsent(new MethodClassKey(method, targetClass),
                key -> Optional.ofNullable(FeatureToggleCondition.resolve(method, targetClass))).orElse(null);
    }
}
