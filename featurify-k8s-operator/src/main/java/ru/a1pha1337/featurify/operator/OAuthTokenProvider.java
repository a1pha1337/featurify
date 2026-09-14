package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;

public final class OAuthTokenProvider {
    private final HttpClient http;
    private final URI tokenUri;
    private final String clientId;
    private final Path secretFile;
    private final ObjectMapper mapper;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    public OAuthTokenProvider(HttpClient http, URI tokenUri, String clientId, Path secretFile, ObjectMapper mapper) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.secretFile = secretFile;
        this.mapper = mapper;
    }

    public synchronized void invalidate() {
        refreshAt = Instant.EPOCH;
    }

    public synchronized String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(refreshAt)) return token;
        String secret = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
        String body = "grant_type=client_credentials&scope=featurify.operator&client_id=" + encode(clientId)
                + "&client_secret=" + encode(secret);
        var request = HttpRequest.newBuilder(tokenUri).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Never propagate the token endpoint response body to logs or CR status.
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "Keycloak token request failed");
        var json = mapper.readTree(response.body());
        String accessToken = json.path("access_token").asText("");
        long expiresIn = json.path("expires_in").asLong(0);
        if (accessToken.isBlank() || expiresIn <= 0) throw new IOException("Invalid Keycloak token response");
        token = accessToken;
        refreshAt = Instant.now().plusSeconds(Math.max(1, expiresIn - Math.min(30, expiresIn / 2)));
        return token;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
