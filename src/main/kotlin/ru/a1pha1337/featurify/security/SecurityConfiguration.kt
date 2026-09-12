package ru.a1pha1337.featurify.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import ru.a1pha1337.featurify.controller.ApiProblems
import ru.a1pha1337.featurify.service.AccessTokenService
import tools.jackson.databind.ObjectMapper

@Configuration
class SecurityConfiguration(
    private val objectMapper: ObjectMapper,
) {
    @Bean
    @Order(1)
    fun featureReadSecurityFilterChain(
        http: HttpSecurity,
        tokens: AccessTokenService,
    ): SecurityFilterChain {
        val paths = listOf("/api/v1/features", "/api/v1/features/{key}", "/api/v1/features:resolve")
        val matcher = PathPatternRequestMatcher.withDefaults()
        http.securityMatcher(
            OrRequestMatcher(
                listOf(HttpMethod.GET, HttpMethod.HEAD).flatMap { method ->
                    paths.map { matcher.matcher(method, it) }
                },
            ),
        )
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.requestCache { it.disable() }
        http.csrf { it.disable() }
        http.authorizeHttpRequests { it.anyRequest().hasAuthority("NAMESPACE_READ") }
        http.addFilterBefore(NamespaceTokenFilter(tokens, objectMapper), AnonymousAuthenticationFilter::class.java)
        http.exceptionHandling { exceptions ->
            exceptions.authenticationEntryPoint { request, response, _ ->
                response.setHeader("WWW-Authenticate", "Bearer realm=\"featurify\"")
                ApiProblems.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "A valid namespace access token is required",
                )
            }
            exceptions.accessDeniedHandler { request, response, _ ->
                ApiProblems.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "Access is denied",
                )
            }
        }
        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests { requests ->
            requests
                .requestMatchers("/actuator/health/**")
                .permitAll()
                .requestMatchers("/api/**")
                .authenticated()
        }
        http.csrf { it.ignoringRequestMatchers("/api/**") }
        http.oauth2ResourceServer { resourceServer ->
            resourceServer.jwt { }
            resourceServer.authenticationEntryPoint { request, response, _ ->
                response.setHeader("WWW-Authenticate", "Bearer realm=\"keycloak\"")
                ApiProblems.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "A valid Keycloak authentication is required",
                )
            }
            resourceServer.accessDeniedHandler { request, response, _ ->
                ApiProblems.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "Access is denied",
                )
            }
        }
        http.exceptionHandling { exceptions ->
            exceptions.authenticationEntryPoint { request, response, _ ->
                if (request.requestURI.startsWith("/api/")) {
                    response.setHeader("WWW-Authenticate", "Bearer realm=\"keycloak\"")
                    ApiProblems.write(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "A valid Keycloak authentication is required",
                    )
                } else {
                    response.sendRedirect("/oauth2/authorization/keycloak")
                }
            }
            exceptions.accessDeniedHandler { request, response, _ ->
                if (request.requestURI.startsWith("/api/")) {
                    ApiProblems.write(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.FORBIDDEN,
                        "FORBIDDEN",
                        "Access is denied",
                    )
                } else {
                    response.sendError(HttpStatus.FORBIDDEN.value())
                }
            }
        }
        http.with(VaadinSecurityConfigurer.vaadin()) { it.oauth2LoginPage("/oauth2/authorization/keycloak") }
        return http.build()
    }
}
