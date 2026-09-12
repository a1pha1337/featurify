package ru.a1pha1337.featurify.controller

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.FeatureToggleService
import tools.jackson.databind.json.JsonMapper

class ApiProblemTests {
    private val tokens = mockk<AccessTokenService>(relaxUnitFun = true)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                AccessTokenController(tokens),
                AdminFeatureController(mockk<FeatureToggleService>()),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()
    private val path = "/api/v1/namespaces/blue/tokens"

    @Test
    fun `invalid JSON and bean validation produce RFC9457 without rejected values`() {
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect { assertThat(it.response.status).isEqualTo(400) }
            .andExpect {
                assertThat(
                    MediaType.parseMediaType(it.response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                ).isTrue()
            }.andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/type").asString(),
                ).isEqualTo("urn:featurify:problem:malformed-json")
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/title").asString()).isEqualTo("Bad Request") }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/status").asInt()).isEqualTo(400) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/detail").asString(),
                ).isEqualTo("Request body is missing or malformed")
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/instance").asString()).isEqualTo(path) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/message").isMissingNode).isTrue() }
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"name":" "}"""))
            .andExpect { assertThat(it.response.status).isEqualTo(400) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                ).isEqualTo("VALIDATION_ERROR")
            }.andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/details/0/field").asString(),
                ).isEqualTo("name")
            }
    }

    @Test
    fun `MVC type mismatch unsupported method and content type use proper client error statuses`() {
        mvc
            .perform(delete("$path/not-a-uuid"))
            .andExpect { assertThat(it.response.status).isEqualTo(400) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/status").asInt()).isEqualTo(400) }
        mvc
            .perform(put(path))
            .andExpect { assertThat(it.response.status).isEqualTo(405) }
            .andExpect { assertThat(it.response.getHeader("Allow")).isNotNull() }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                ).isEqualTo("METHOD_NOT_ALLOWED")
            }
        mvc
            .perform(post(path).contentType(MediaType.TEXT_PLAIN).content("test"))
            .andExpect { assertThat(it.response.status).isEqualTo(415) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/status").asInt()).isEqualTo(415) }
    }

    @Test
    fun `conflict storage failure and unexpected errors have stable codes and safe details`() {
        every { tokens.list("blue") } throws ConflictException("Resource conflict")
        mvc
            .perform(get(path))
            .andExpect {
                assertThat(it.response.status).isEqualTo(409)
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/code").asString()).isEqualTo("CONFLICT") }
        clearMocks(tokens)
        every { tokens.list("blue") } throws DataAccessResourceFailureException("private JDBC connection string")
        mvc
            .perform(get(path))
            .andExpect { assertThat(it.response.status).isEqualTo(503) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/detail").asString(),
                ).isEqualTo("Service temporarily unavailable")
            }
        clearMocks(tokens)
        every { tokens.list("blue") } throws IllegalStateException("private implementation details")
        mvc
            .perform(get(path))
            .andExpect { assertThat(it.response.status).isEqualTo(500) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/detail").asString(),
                ).isEqualTo("An unexpected error occurred")
            }.andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                ).isEqualTo("INTERNAL_ERROR")
            }
    }
}
