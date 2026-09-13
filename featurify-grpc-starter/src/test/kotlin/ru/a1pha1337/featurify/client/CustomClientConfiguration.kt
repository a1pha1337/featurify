package ru.a1pha1337.featurify.client

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse

@Configuration(proxyBeanMethods = false)
class CustomClientConfiguration {
    @Bean
    fun customClient(): FeaturifyClient =
        object : FeaturifyClient {
            override fun getBooleanFeature(
                key: String,
                group: String?,
            ) = BooleanFeatureResponse.newBuilder().setValue(true).build()

            override fun getEnumFeature(
                key: String,
                group: String?,
            ) = EnumFeatureResponse.getDefaultInstance()

            override fun getVectorFeature(
                key: String,
                element: String,
                group: String?,
            ) = BooleanFeatureResponse.getDefaultInstance()
        }
}
