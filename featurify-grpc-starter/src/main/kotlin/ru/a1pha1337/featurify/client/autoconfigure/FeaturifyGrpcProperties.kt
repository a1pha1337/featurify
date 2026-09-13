package ru.a1pha1337.featurify.client.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import java.time.Duration

// Not a data class: credentials must not appear in a generated toString().
@ConfigurationProperties("featurify.grpc")
class FeaturifyGrpcProperties {
    var enabled: Boolean = true
    var host: String = "localhost"
    var port: Int = 9090
    var token: String = ""
    var timeout: Duration = Duration.ofSeconds(2)
    var tls: Boolean = true
    var trustCertificate: Resource? = null
}
