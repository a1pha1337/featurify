package ru.a1pha1337.featurify.client

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.a1pha1337.featurify.client.service.BooleanFeatureService
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class CustomServiceConfiguration {
    @Bean
    fun customBooleanService(client: FeaturifyClient) = BooleanFeatureService(client, Duration.ofSeconds(5), 100)
}
