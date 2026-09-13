package ru.a1pha1337.featurify.client.autoconfigure

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import ru.a1pha1337.featurify.client.FeaturifyClient
import ru.a1pha1337.featurify.client.GrpcFeaturifyClient

@AutoConfiguration
@ConditionalOnClass(ManagedChannel::class)
@ConditionalOnMissingBean(FeaturifyClient::class)
@ConditionalOnProperty(prefix = "featurify.grpc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FeaturifyGrpcProperties::class)
class FeaturifyGrpcAutoConfiguration {
    @Bean(destroyMethod = "close")
    fun featurifyClient(properties: FeaturifyGrpcProperties): GrpcFeaturifyClient {
        require(properties.host.isNotBlank()) { "featurify.grpc.host must not be blank" }
        require(properties.port in 1..65535) { "featurify.grpc.port must be between 1 and 65535" }
        require(properties.tls || properties.trustCertificate == null) { "featurify.grpc.trust-certificate requires TLS" }
        val builder = NettyChannelBuilder.forAddress(properties.host, properties.port).disableRetry()
        if (properties.tls) {
            val ssl = GrpcSslContexts.forClient()
            properties.trustCertificate?.inputStream?.use { ssl.trustManager(it) }
            builder.sslContext(ssl.build())
        } else {
            builder.usePlaintext()
        }
        val channel = builder.build()
        return try {
            GrpcFeaturifyClient(channel, properties.token, properties.timeout)
        } catch (exception: RuntimeException) {
            channel.shutdownNow()
            throw exception
        }
    }
}
