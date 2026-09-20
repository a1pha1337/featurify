package ru.a1pha1337.featurify.client.autoconfigure;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import ru.a1pha1337.featurify.client.toggle.FeatureToggleAdvisor;
import ru.a1pha1337.featurify.client.toggle.FeatureToggleValidator;

@Configuration
@Import(FeatureToggleProxyRegistrar.class)
public class FeatureToggleConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static FeatureToggleAdvisor featurifyFeatureToggleAdvisor(BeanFactory beans) {
        return new FeatureToggleAdvisor(beans);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static FeatureToggleValidator featurifyFeatureToggleValidator(FeatureToggleAdvisor advisor, Environment environment) {
        return new FeatureToggleValidator(advisor, environment.getProperty("spring.aop.proxy-target-class", Boolean.class, true));
    }
}
