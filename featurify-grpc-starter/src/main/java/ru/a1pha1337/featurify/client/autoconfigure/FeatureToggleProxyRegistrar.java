package ru.a1pha1337.featurify.client.autoconfigure;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/** Uses the shared Spring auto-proxy creator, including when transactions/AOP are already enabled. */
public final class FeatureToggleProxyRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {
    private boolean proxyTargetClass;

    @Override
    public void setEnvironment(Environment environment) {
        proxyTargetClass = environment.getProperty("spring.aop.proxy-target-class", Boolean.class, true);
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        if (proxyTargetClass) AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
    }
}
