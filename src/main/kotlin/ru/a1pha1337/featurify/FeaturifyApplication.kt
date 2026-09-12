package ru.a1pha1337.featurify

import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.lumo.Lumo
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.runApplication

// Keycloak protects HTTP endpoints; gRPC uses TokenAuthenticationInterceptor.
@SpringBootApplication(exclude = [GrpcServerOAuth2ResourceServerAutoConfiguration::class])
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("styles.css")
class FeaturifyApplication : AppShellConfigurator

fun main(args: Array<String>) {
	runApplication<FeaturifyApplication>(*args)
}
