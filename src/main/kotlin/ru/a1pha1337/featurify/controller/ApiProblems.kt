package ru.a1pha1337.featurify.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import ru.a1pha1337.featurify.dto.ValidationDetail
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Locale

object ApiProblems {
    fun create(status: HttpStatusCode, code: String, detail: String, path: String,
               details: List<ValidationDetail> = emptyList()): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("urn:featurify:problem:${code.lowercase(Locale.ROOT).replace('_', '-')}")
            instance = URI.create(path)
            setProperty("code", code)
            if (details.isNotEmpty()) setProperty("details", details)
        }

    fun write(mapper: ObjectMapper, request: HttpServletRequest, response: HttpServletResponse,
              status: HttpStatusCode, code: String, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        mapper.writeValue(response.outputStream, create(status, code, detail, request.requestURI))
    }
}
