package ru.a1pha1337.featurify.grpc

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles

/** Runs the same RPC and authentication checks over TLS with certificate verification. */
@ActiveProfiles("grpc-tls")
@SpringBootTest(
    properties = [
        "spring.grpc.server.port=0", "spring.grpc.server.address=127.0.0.1", "vaadin.productionMode=true",
        "GRPC_TLS_CERTIFICATE=classpath:grpc-tls/server.crt", "GRPC_TLS_PRIVATE_KEY=classpath:grpc-tls/server.key",
    ],
)
class GrpcTlsServerDatabaseTests : GrpcServerDatabaseTests() {
    override fun createChannel(): ManagedChannel {
        val sslContext =
            ClassPathResource("grpc-tls/server.crt").inputStream.use {
                GrpcSslContexts.forClient().trustManager(it).build()
            }
        return NettyChannelBuilder.forAddress("localhost", port).sslContext(sslContext).build()
    }
}
