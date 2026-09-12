package ru.a1pha1337.featurify.grpc

import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

@Component
class GrpcServerLifecycle(
    private val features: FeatureGrpcService,
    private val authentication: TokenAuthenticationInterceptor,
    @Value("\${grpc.server.port:9090}") private val port: Int,
    @Value("\${grpc.server.address:0.0.0.0}") private val address: String,
    @Value("\${grpc.server.enabled:true}") private val enabled: Boolean,
    @Value("\${grpc.server.certificate:}") private val certificate: String,
    @Value("\${grpc.server.private-key:}") private val privateKey: String,
) : SmartLifecycle {
    private var server: Server? = null

    @Synchronized
    override fun start() {
        if (!enabled || isRunning) return
        require(certificate.isBlank() == privateKey.isBlank()) { "Configure both gRPC certificate and private key" }
        val builder = NettyServerBuilder.forAddress(InetSocketAddress(address, port))
            .maxInboundMessageSize(16 * 1024)
            .addService(ServerInterceptors.intercept(features, authentication))
        if (certificate.isNotBlank()) builder.useTransportSecurity(File(certificate), File(privateKey))
        server = builder.build().start()
    }

    @Synchronized
    override fun stop() {
        val running = server ?: return
        try {
            running.shutdown()
            if (!running.awaitTermination(5, TimeUnit.SECONDS)) running.shutdownNow()
        } catch (exception: InterruptedException) {
            running.shutdownNow()
            Thread.currentThread().interrupt()
        } finally {
            server = null
        }
    }

    override fun isRunning() = server?.let { !it.isShutdown } ?: false
    override fun isAutoStartup() = enabled
}
