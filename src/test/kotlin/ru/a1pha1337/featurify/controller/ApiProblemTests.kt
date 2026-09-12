package ru.a1pha1337.featurify.controller

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.FeatureToggleService

class ApiProblemTests {
    private val tokens = mock(AccessTokenService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                AccessTokenController(tokens),
                AdminFeatureController(mock(FeatureToggleService::class.java)),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()
    private val path = "/api/v1/namespaces/blue/tokens"

    @Test
    fun `invalid JSON and bean validation produce RFC9457 without rejected values`() {
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:featurify:problem:malformed-json"))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Request body is missing or malformed"))
            .andExpect(jsonPath("$.instance").value(path))
            .andExpect(jsonPath("$.message").doesNotExist())
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"name":" "}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0].field").value("name"))
    }

    @Test
    fun `MVC type mismatch unsupported method and content type use proper client error statuses`() {
        mvc
            .perform(delete("$path/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
        mvc
            .perform(put(path))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        mvc
            .perform(post(path).contentType(MediaType.TEXT_PLAIN).content("test"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.status").value(415))
    }

    @Test
    fun `conflict storage failure and unexpected errors have stable codes and safe details`() {
        `when`(tokens.list("blue")).thenThrow(ConflictException("Resource conflict"))
        mvc.perform(get(path)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
        reset(tokens)
        `when`(tokens.list("blue")).thenThrow(DataAccessResourceFailureException("private JDBC connection string"))
        mvc
            .perform(get(path))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.detail").value("Service temporarily unavailable"))
        reset(tokens)
        `when`(tokens.list("blue")).thenThrow(IllegalStateException("private implementation details"))
        mvc
            .perform(get(path))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
    }
}
