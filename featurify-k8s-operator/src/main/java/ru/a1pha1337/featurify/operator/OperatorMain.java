package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javaoperatorsdk.operator.Operator;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public final class OperatorMain {
    private OperatorMain() {
    }

    public static void main(String[] args) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var mapper = new ObjectMapper();
        var tokens = new OAuthTokenProvider(http, endpoint("KEYCLOAK_TOKEN_URL"), required("KEYCLOAK_CLIENT_ID"),
                Path.of(required("KEYCLOAK_CLIENT_SECRET_FILE")), mapper);
        String clusterId = required("FEATURIFY_CLUSTER_ID");
        if (!clusterId.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")) throw new IllegalArgumentException("Invalid FEATURIFY_CLUSTER_ID");
        var client = new HttpManifestClient(http, endpoint("FEATURIFY_URL"), clusterId, tokens, mapper);
        long seconds = Long.parseLong(System.getenv().getOrDefault("RECONCILE_INTERVAL_SECONDS", "30"));
        if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("Reconcile interval must be 5-3600 seconds");
        // A single namespace per process keeps its RBAC and service credentials scoped together.
        String namespace = required("WATCH_NAMESPACE");
        Operator operator = new Operator(overrider -> overrider.withUseSSAToPatchPrimaryResource(false));
        operator.register(new NamespaceReconciler(client, Duration.ofSeconds(seconds), Clock.systemUTC()),
                config -> config.settingNamespace(namespace));
        Runtime.getRuntime().addShutdownHook(new Thread(operator::stop, "operator-shutdown"));
        operator.start();
    }

    private static URI endpoint(String name) {
        URI uri = URI.create(required(name));
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid endpoint configuration: " + name);
        }
        return uri;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
}
