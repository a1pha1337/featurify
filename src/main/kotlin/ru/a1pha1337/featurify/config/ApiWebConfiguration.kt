package ru.a1pha1337.featurify.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.HttpRequestHandler
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import ru.a1pha1337.featurify.controller.ApiProblems
import tools.jackson.databind.ObjectMapper

@Configuration
class ApiWebConfiguration {
    @Bean
    fun apiNotFoundHandlerMapping(mapper: ObjectMapper): SimpleUrlHandlerMapping {
        val handler = HttpRequestHandler { request, response ->
            ApiProblems.write(mapper, request, response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource was not found")
        }
        // After annotated REST controllers (order 0), before Vaadin's catch-all mapping.
        // MVC still handles method/media-type errors for existing endpoints itself.
        return SimpleUrlHandlerMapping(mapOf("/api/**" to handler), 1)
    }
}
