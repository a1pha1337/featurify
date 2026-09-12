package ru.a1pha1337.featurify.grpc

import io.grpc.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.a1pha1337.featurify.service.AccessTokenService
import java.util.UUID

@Component
class TokenAuthenticationInterceptor(private val tokens: AccessTokenService) : ServerInterceptor {
    companion object {
        val NAMESPACE: Context.Key<UUID> = Context.key("authenticated-namespace")
        val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val credentials = headers.getAll(AUTHORIZATION)?.toList().orEmpty()
        val header = credentials.singleOrNull()
        val namespace = try {
            if (header != null && header.startsWith("Bearer ") && header.length <= 100) {
                tokens.authenticate(header.substring(7))
            } else null
        } catch (exception: RuntimeException) {
            // Never include metadata or credentials in logs or error descriptions.
            LoggerFactory.getLogger(javaClass).error("Access token authentication storage failed")
            call.close(Status.UNAVAILABLE.withDescription("Authentication temporarily unavailable"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        if (namespace == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("A valid namespace access token is required"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return Contexts.interceptCall(Context.current().withValue(NAMESPACE, namespace), call, headers, next)
    }
}
