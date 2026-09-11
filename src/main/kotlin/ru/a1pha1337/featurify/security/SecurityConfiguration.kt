package ru.a1pha1337.featurify.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import ru.a1pha1337.featurify.dto.ApiError
import tools.jackson.databind.ObjectMapper

@Configuration
class SecurityConfiguration(private val objectMapper: ObjectMapper) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests { requests ->
            requests
                .requestMatchers(HttpMethod.GET, "/api/v1/features").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/features/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/features:resolve").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/tenants/**").authenticated()
                .requestMatchers("/api/v1/groups/**").authenticated()
                .requestMatchers("/api/v1/features/**", "/api/v1/features").authenticated()
        }
        http.csrf { csrf -> csrf.ignoringRequestMatchers("/api/**") }
        http.oauth2ResourceServer { resourceServer ->
            resourceServer.jwt { }
            resourceServer.authenticationEntryPoint { _, response, _ ->
                writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ApiError("UNAUTHORIZED", "Bearer token is missing or invalid"),
                )
            }
            resourceServer.accessDeniedHandler { _, response, _ ->
                writeError(response, HttpServletResponse.SC_FORBIDDEN, ApiError("FORBIDDEN", "Access is denied"))
            }
        }
        http.exceptionHandling { exceptions ->
            exceptions.authenticationEntryPoint { request, response, _ ->
                if (request.requestURI.startsWith("/api/")) {
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ApiError("UNAUTHORIZED", "Authentication is required"))
                } else {
                    response.sendRedirect("/oauth2/authorization/keycloak")
                }
            }
            exceptions.accessDeniedHandler { request, response, _ ->
                if (request.requestURI.startsWith("/api/")) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, ApiError("FORBIDDEN", "Access is denied"))
                } else {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                }
            }
        }
        http.with(VaadinSecurityConfigurer.vaadin()) { vaadin ->
            vaadin.oauth2LoginPage("/oauth2/authorization/keycloak")
        }
        return http.build()
    }

    private fun writeError(response: HttpServletResponse, status: Int, error: ApiError) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, error)
    }
}
