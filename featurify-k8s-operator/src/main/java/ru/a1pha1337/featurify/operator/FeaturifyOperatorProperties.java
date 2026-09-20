package ru.a1pha1337.featurify.operator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("featurify.operator")
public record FeaturifyOperatorProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?") String watchNamespace,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}") String clusterId,
        @NotNull URI url,
        @NotNull URI tokenUrl,
        @NotBlank String clientId,
        @NotNull Path clientSecretFile,
        @Min(5) @Max(3600) long reconcileIntervalSeconds) {

    public FeaturifyOperatorProperties {
        validateEndpoint(url, "FEATURIFY_URL");
        validateEndpoint(tokenUrl, "KEYCLOAK_TOKEN_URL");
        if (clientSecretFile != null && clientSecretFile.toString().isBlank()) {
            throw new IllegalArgumentException("Missing KEYCLOAK_CLIENT_SECRET_FILE");
        }
    }

    private static void validateEndpoint(URI uri, String name) {
        if (uri != null && (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)) {
            throw new IllegalArgumentException("Invalid endpoint configuration: " + name);
        }
    }
}
