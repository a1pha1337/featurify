package ru.a1pha1337.featurify.client.toggle;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reject invalid declarations when a bean is initialized, without performing any RPC. */
public final class FeatureToggleValidator implements BeanPostProcessor {
    private final FeatureToggleAdvisor advisor;
    private final boolean proxyTargetClass;

    public FeatureToggleValidator(FeatureToggleAdvisor advisor, boolean proxyTargetClass) {
        this.advisor = advisor;
        this.proxyTargetClass = proxyTargetClass;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> targetClass = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
        ReflectionUtils.doWithMethods(targetClass, method -> validate(method, targetClass));
        for (Class<?> contract : ClassUtils.getAllInterfacesForClass(targetClass)) {
            for (Method method : contract.getMethods()) validate(method, targetClass);
        }
        return bean;
    }

    private void validate(Method method, Class<?> targetClass) {
        if (!advisor.matches(method, targetClass)) return;
        Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(targetClass);
        if (proxyTargetClass || interfaces.length == 0) {
            if (Modifier.isFinal(targetClass.getModifiers())) {
                throw new IllegalArgumentException("Feature toggle requires a non-final class for class proxies: " + targetClass.getName());
            }
        } else {
            Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
            for (Class<?> contract : interfaces) {
                for (Method candidate : contract.getMethods()) {
                    if (AopUtils.getMostSpecificMethod(candidate, targetClass).equals(specific)) return;
                }
            }
            throw new IllegalArgumentException("Feature toggle method is not exposed by a proxy interface: " + method.toGenericString());
        }
    }
}
