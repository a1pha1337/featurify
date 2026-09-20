package ru.a1pha1337.featurify.demo

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.PayloadFeatureResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "featurify.grpc.token=demo-test-token",
        "featurify.grpc.default-group=checkout",
        "featurify.grpc.cache.ttl=1h",
        "featurify.grpc.timeout=1s",
    ],
)
class DiagnosticHttpTests {
    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper()

    @Test
    fun `HTTP endpoints read all feature types through the authenticated grpc client`() {
        val boolean = body("/diagnostics/features/boolean/off")
        assertThat(boolean["value"].asBoolean()).isFalse()
        assertThat(boolean["type"].asString()).isEqualTo("BOOLEAN")
        assertThat(boolean["key"].asString()).isEqualTo("off")
        assertThat(boolean["group"].asString()).isEqualTo("checkout")
        assertThat(boolean["version"].asLong()).isPositive()
        assertThat(boolean["elapsedMicros"].asLong()).isNotNegative()
        assertThat(boolean["cacheAllowed"].asBoolean()).isTrue()

        assertThat(body("/diagnostics/features/enum/provider")["value"].asString()).isEqualTo("checkout")
        val vector = body("/diagnostics/features/vector/animals/DOG")
        assertThat(vector["value"].asBoolean()).isTrue()
        assertThat(vector["element"].asString()).isEqualTo("DOG")
        assertThat(body("/diagnostics/features/vector/animals/CAT")["value"].asBoolean()).isFalse()
        assertThat(requests).contains("boolean:off:checkout", "enum:provider:checkout", "vector:animals:DOG:checkout")
    }

    @Test
    fun `omitted explicit and global groups work in cached and direct modes`() {
        for (cached in listOf(true, false)) {
            val key = "groups-$cached"
            assertThat(body("/diagnostics/features/enum/$key?cached=$cached")["value"].asString()).isEqualTo("checkout")
            val explicit = body("/diagnostics/features/enum/$key?group=other&cached=$cached")
            assertThat(explicit["value"].asString()).isEqualTo("other")
            assertThat(explicit["group"].asString()).isEqualTo("other")
            val global = body("/diagnostics/features/enum/$key?group=&cached=$cached")
            assertThat(global["group"].isNull).isTrue()
            assertThat(global["value"].asString()).isEmpty()
        }
    }

    @Test
    fun `cached false bypasses the service cache for every feature type`() {
        for (path in listOf("boolean/cache-boolean", "enum/cache-enum", "vector/cache-vector/DOG", "payload/cache-payload")) {
            val url = "/diagnostics/features/$path"
            val initial = body(url)["version"].asLong()
            assertThat(body(url)["version"].asLong()).isEqualTo(initial)
            assertThat(body("$url?group=checkout")["version"].asLong()).isEqualTo(initial)
            val direct = body("$url?cached=false")
            assertThat(direct["version"].asLong()).isGreaterThan(initial)
            assertThat(direct["cacheAllowed"].asBoolean()).isFalse()
            assertThat(body("$url?cached=false")["version"].asLong()).isGreaterThan(direct["version"].asLong())
            assertThat(body(url)["version"].asLong()).isEqualTo(initial)
        }
    }

    @Test
    fun `client diagnostics omit credentials and health does not issue an RPC`() {
        val before = requests.size
        val response = get("/diagnostics/client")
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).doesNotContain("demo-test-token", "token", "trustCertificate")
        val config = json.readTree(response.body())
        assertThat(config["host"].asString()).isEqualTo("localhost")
        assertThat(config["port"].asInt()).isEqualTo(grpcServer.port)
        assertThat(config["tls"].asBoolean()).isFalse()
        assertThat(config["timeout"].asString()).isEqualTo("PT1S")
        assertThat(config["cache"]["ttl"].asString()).isEqualTo("PT1H")
        assertThat(body("/actuator/health")["status"].asString()).isEqualTo("UP")
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404)
        assertThat(requests.size).isEqualTo(before)
    }

    @Test
    fun `grpc failures produce diagnostic HTTP statuses and are not cached`() {
        val statuses =
            mapOf(
                Status.Code.INVALID_ARGUMENT to 400,
                Status.Code.UNAUTHENTICATED to 401,
                Status.Code.PERMISSION_DENIED to 403,
                Status.Code.NOT_FOUND to 404,
                Status.Code.FAILED_PRECONDITION to 409,
                Status.Code.RESOURCE_EXHAUSTED to 429,
                Status.Code.UNAVAILABLE to 503,
                Status.Code.INTERNAL to 502,
            )
        for ((code, httpStatus) in statuses) {
            val key = "error-${code.name}"
            val before = requests.count { it == "boolean:$key:checkout" }
            repeat(2) {
                val response = get("/diagnostics/features/boolean/$key")
                assertThat(response.statusCode()).isEqualTo(httpStatus)
                assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json")
                val problem = json.readTree(response.body())
                assertThat(problem["grpcCode"].asString()).isEqualTo(code.name)
                assertThat(problem["detail"].asString()).isEqualTo("Test failure")
            }
            assertThat(requests.count { it == "boolean:$key:checkout" }).isEqualTo(before + 2)
        }
    }

    @Test
    fun `RPC deadline and invalid HTTP parameters are diagnosed`() {
        val response = get("/diagnostics/features/boolean/slow?cached=false")
        assertThat(response.statusCode()).isEqualTo(504)
        assertThat(json.readTree(response.body())["grpcCode"].asString()).isEqualTo("DEADLINE_EXCEEDED")
        val before = requests.size
        assertThat(get("/diagnostics/features/boolean/invalid?cached=oops").statusCode()).isEqualTo(400)
        assertThat(requests.size).isEqualTo(before)
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun body(path: String): JsonNode {
        val response = get(path)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return json.readTree(response.body())
    }

    companion object {
        private val requests = CopyOnWriteArrayList<String>()
        private val version = AtomicLong()
        private val grpcServer =
            NettyServerBuilder
                .forPort(0)
                .intercept(
                    object : ServerInterceptor {
                        override fun <ReqT : Any, RespT : Any> interceptCall(
                            call: ServerCall<ReqT, RespT>,
                            headers: Metadata,
                            next: ServerCallHandler<ReqT, RespT>,
                        ): ServerCall.Listener<ReqT> {
                            val authorization = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER))
                            if (authorization != "Bearer demo-test-token") {
                                call.close(Status.UNAUTHENTICATED, Metadata())
                                return object : ServerCall.Listener<ReqT>() {}
                            }
                            return next.startCall(call, headers)
                        }
                    },
                ).addService(
                    object : FeatureServiceGrpc.FeatureServiceImplBase() {
                        override fun getBooleanFeature(
                            request: GetFeatureRequest,
                            observer: StreamObserver<BooleanFeatureResponse>,
                        ) {
                            requests.add("boolean:${request.key}:${request.group}")
                            if (request.key == "slow") return
                            if (request.key.startsWith("error-")) {
                                val code = Status.Code.valueOf(request.key.removePrefix("error-"))
                                observer.onError(Status.fromCode(code).withDescription("Test failure").asRuntimeException())
                                return
                            }
                            observer.onNext(
                                BooleanFeatureResponse
                                    .newBuilder()
                                    .setValue(request.key != "off")
                                    .setVersion(version.incrementAndGet())
                                    .build(),
                            )
                            observer.onCompleted()
                        }

                        override fun getEnumFeature(
                            request: GetFeatureRequest,
                            observer: StreamObserver<EnumFeatureResponse>,
                        ) {
                            requests.add("enum:${request.key}:${request.group}")
                            observer.onNext(
                                EnumFeatureResponse
                                    .newBuilder()
                                    .setValue(request.group)
                                    .setVersion(version.incrementAndGet())
                                    .build(),
                            )
                            observer.onCompleted()
                        }

                        override fun getVectorFeature(
                            request: GetVectorFeatureRequest,
                            observer: StreamObserver<BooleanFeatureResponse>,
                        ) {
                            requests.add("vector:${request.key}:${request.element}:${request.group}")
                            observer.onNext(
                                BooleanFeatureResponse
                                    .newBuilder()
                                    .setValue(request.element == "DOG")
                                    .setVersion(version.incrementAndGet())
                                    .build(),
                            )
                            observer.onCompleted()
                        }

                        override fun getPayloadFeature(
                            request: GetFeatureRequest,
                            observer: StreamObserver<PayloadFeatureResponse>,
                        ) {
                            requests.add("payload:${request.key}:${request.group}")
                            observer.onNext(
                                PayloadFeatureResponse
                                    .newBuilder()
                                    .setValue(
                                        "{\"enabled\":true}",
                                    ).setVersion(version.incrementAndGet())
                                    .build(),
                            )
                            observer.onCompleted()
                        }
                    },
                ).build()
                .start()

        @JvmStatic
        @DynamicPropertySource
        fun grpcProperties(registry: DynamicPropertyRegistry) {
            registry.add("featurify.grpc.host") { "localhost" }
            registry.add("featurify.grpc.port") { grpcServer.port }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
