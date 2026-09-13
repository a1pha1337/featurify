package ru.a1pha1337.featurify.client

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcAutoConfiguration
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcProperties
import ru.a1pha1337.featurify.client.service.BooleanFeatureService
import ru.a1pha1337.featurify.client.service.EnumFeatureService
import ru.a1pha1337.featurify.client.service.VectorFeatureService
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import java.time.Duration
import java.util.concurrent.TimeUnit

class FeaturifyGrpcAutoConfigurationTests {
    private val runner =
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeaturifyGrpcAutoConfiguration::class.java))

    @Test
    fun `starter can be disabled without credentials`() {
        runner.withPropertyValues("featurify.grpc.enabled=false").run {
            assertThat(it).hasNotFailed().doesNotHaveBean(FeaturifyClient::class.java)
            assertThat(it).doesNotHaveBean(BooleanFeatureService::class.java)
            assertThat(it).doesNotHaveBean(EnumFeatureService::class.java)
            assertThat(it).doesNotHaveBean(VectorFeatureService::class.java)
        }
    }

    @Test
    fun `user client overrides auto configuration without credentials`() {
        runner.withUserConfiguration(CustomClientConfiguration::class.java).run {
            assertThat(it).hasNotFailed().hasSingleBean(FeaturifyClient::class.java)
            assertThat(it.getBean(FeaturifyClient::class.java).getBooleanFeature("test").value).isTrue()
            assertThat(it).hasSingleBean(BooleanFeatureService::class.java)
            assertThat(it).hasSingleBean(EnumFeatureService::class.java)
            assertThat(it).hasSingleBean(VectorFeatureService::class.java)
            assertThat(it.getBean(BooleanFeatureService::class.java).isEnabled("test")).isTrue()
            assertThat(it.getBean(FeaturifyGrpcProperties::class.java).cache.ttl).isEqualTo(Duration.ofSeconds(1))
            assertThat(it.getBean(FeaturifyGrpcProperties::class.java).cache.maximumSize).isEqualTo(10_000)
        }
    }

    @Test
    fun `cache properties bind for a custom client and zero ttl bypasses caching`() {
        runner
            .withUserConfiguration(CustomClientConfiguration::class.java)
            .withPropertyValues("featurify.grpc.cache.ttl=0s", "featurify.grpc.cache.maximum-size=25")
            .run {
                assertThat(it).hasNotFailed()
                val cache = it.getBean(FeaturifyGrpcProperties::class.java).cache
                assertThat(cache.ttl).isEqualTo(Duration.ZERO)
                assertThat(cache.maximumSize).isEqualTo(25)
                val service = it.getBean(BooleanFeatureService::class.java)
                assertThat(service.getFeature("test")).isNotSameAs(service.getFeature("test"))
            }
    }

    @Test
    fun `custom service overrides only its own default bean`() {
        runner
            .withUserConfiguration(CustomClientConfiguration::class.java, CustomServiceConfiguration::class.java)
            .run {
                assertThat(it).hasNotFailed().hasSingleBean(BooleanFeatureService::class.java)
                assertThat(it.getBean(BooleanFeatureService::class.java)).isSameAs(it.getBean("customBooleanService"))
                assertThat(it)
                    .hasSingleBean(EnumFeatureService::class.java)
                    .hasSingleBean(VectorFeatureService::class.java)
            }
    }

    @Test
    fun `missing token fails startup`() {
        runner.run { assertThat(it).hasFailed() }
    }

    @Test
    fun `invalid configuration fails startup`() {
        listOf(
            "featurify.grpc.port=0",
            "featurify.grpc.host=",
            "featurify.grpc.timeout=0s",
            "featurify.grpc.timeout=-1s",
            "featurify.grpc.timeout=2d",
            "featurify.grpc.cache.ttl=-1s",
            "featurify.grpc.cache.maximum-size=0",
            "featurify.grpc.cache.maximum-size=-1",
        ).forEach { property ->
            runner.withPropertyValues("featurify.grpc.token=test-token", property).run { assertThat(it).hasFailed() }
        }
        runner
            .withPropertyValues(
                "featurify.grpc.token=test-token",
                "featurify.grpc.tls=false",
                "featurify.grpc.trust-certificate=classpath:grpc-tls/server.crt",
            ).run { assertThat(it).hasFailed() }
    }

    @Test
    fun `auto configuration is discovered from the starter jar and closes plaintext connection`() {
        verifyConnection(tls = false)
    }

    @Test
    fun `TLS is enabled by default and supports a custom trust certificate`() {
        verifyConnection(tls = true)
    }

    private fun verifyConnection(tls: Boolean) {
        val builder =
            NettyServerBuilder.forPort(0).addService(
                object : FeatureServiceGrpc.FeatureServiceImplBase() {
                    override fun getBooleanFeature(
                        request: GetFeatureRequest,
                        observer: StreamObserver<BooleanFeatureResponse>,
                    ) {
                        observer.onNext(
                            BooleanFeatureResponse
                                .newBuilder()
                                .setValue(true)
                                .setVersion(42)
                                .build(),
                        )
                        observer.onCompleted()
                    }
                },
            )
        if (tls) {
            javaClass.getResourceAsStream("/grpc-tls/server.crt")!!.use { cert ->
                javaClass.getResourceAsStream("/grpc-tls/server.key")!!.use { key ->
                    builder.useTransportSecurity(cert, key)
                }
            }
        }
        val server = builder.build().start()
        try {
            lateinit var client: FeaturifyClient
            ApplicationContextRunner()
                .withUserConfiguration(DiscoveryConfiguration::class.java)
                .withPropertyValues(
                    "featurify.grpc.host=localhost",
                    "featurify.grpc.port=${server.port}",
                    "featurify.grpc.token=test-token",
                    "featurify.grpc.timeout=5s",
                    if (tls) "featurify.grpc.trust-certificate=classpath:grpc-tls/server.crt" else "featurify.grpc.tls=false",
                ).run {
                    assertThat(it).hasNotFailed().hasSingleBean(FeaturifyClient::class.java)
                    client = it.getBean(FeaturifyClient::class.java)
                    assertThat(client.getBooleanFeature("enabled").version).isEqualTo(42)
                    assertThat(
                        it.getBean(BooleanFeatureService::class.java).getFeature("enabled").version,
                    ).isEqualTo(42)
                }
            val error = catchThrowableOfType(StatusRuntimeException::class.java) { client.getBooleanFeature("enabled") }
            assertThat(error.status.code).isEqualTo(Status.Code.UNAVAILABLE)
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
