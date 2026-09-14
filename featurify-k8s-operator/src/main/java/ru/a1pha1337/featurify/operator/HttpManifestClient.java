package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class HttpManifestClient implements ManifestClient {
    private final HttpClient http;
    private final URI baseUri;
    private final String clusterId;
    private final OAuthTokenProvider tokens;
    private final ObjectMapper mapper;

    public HttpManifestClient(HttpClient http, URI baseUri, String clusterId, OAuthTokenProvider tokens, ObjectMapper mapper) {
        this.http = http;
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", "") + "/");
        this.clusterId = clusterId;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    public void apply(FeaturifyNamespace resource) throws IOException, InterruptedException {
        send(resource, false);
    }

    @Override
    public void cleanup(FeaturifyNamespace resource) throws IOException, InterruptedException {
        send(resource, true);
    }

    private void send(FeaturifyNamespace resource, boolean cleanup) throws IOException, InterruptedException {
        String key = resource.getSpec().key();
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]*(?:[-.][A-Za-z0-9]+)*") || key.length() > 255) {
            throw new ApiException(400, "Invalid namespace key");
        }
        var metadata = resource.getMetadata();
        var owner = Map.of("clusterId", clusterId, "uid", metadata.getUid(),
                "kubernetesNamespace", metadata.getNamespace(), "name", metadata.getName());
        var body = cleanup
                ? Map.of("owner", owner, "generation", metadata.getGeneration(),
                    "deletionPolicy", resource.getSpec().management().deletionPolicy())
                : Map.of("owner", owner, "generation", metadata.getGeneration(), "spec", resource.getSpec());
        URI uri = baseUri.resolve("api/v1/operator/namespaces/" + key + (cleanup ? "/cleanup" : ""));
        for (int attempt = 0; attempt < 2; attempt++) {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + tokens.token())
                    .header("Content-Type", "application/json")
                    .method(cleanup ? "POST" : "PUT", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 && attempt == 0) {
                tokens.invalidate();
                continue;
            }
            if (response.statusCode() == (cleanup ? 204 : 200)) return;
            String detail = "Featurify request failed (HTTP " + response.statusCode() + ")";
            if (response.statusCode() == 400 || response.statusCode() == 409) {
                try {
                    String serverDetail = mapper.readTree(response.body()).path("detail").asText("");
                    if (!serverDetail.isBlank()) detail = serverDetail.substring(0, Math.min(1000, serverDetail.length()));
                } catch (IOException ignored) {
                    // A proxy can return HTML. Do not include arbitrary response bodies in status.
                }
            }
            throw new ApiException(response.statusCode(), detail);
        }
    }
}
