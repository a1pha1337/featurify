package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class HttpManifestClientTests {
    @TempDir Path directory;

    @Test
    void sendsOwnerAndPoliciesAndRefreshesRejectedTokenWithoutResettingSpec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        List<JsonNode> bodies = new ArrayList<>();
        List<String> authorization = new ArrayList<>();
        server.createContext("/token", exchange -> {
            int number = tokens.incrementAndGet();
            byte[] body = ("{\"access_token\":\"test-token-" + number + "\",\"expires_in\":300}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/operator/namespaces/checkout", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = requests.incrementAndGet() == 1 ? 401 : exchange.getRequestMethod().equals("POST") ? 204 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            Path secret = directory.resolve("client-secret");
            Files.writeString(secret, "test-secret");
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient http = HttpClient.newHttpClient();
            var tokenProvider = new OAuthTokenProvider(http, base.resolve("/token"), "operator", secret, mapper);
            var client = new HttpManifestClient(http, base, "test-cluster", tokenProvider, mapper);
            var resource = NamespaceReconcilerTests.resource();
            client.apply(resource);
            client.cleanup(resource);
            assertThat(tokens.get()).isEqualTo(2);
            assertThat(authorization).containsExactly("Bearer test-token-1", "Bearer test-token-2", "Bearer test-token-2");
            assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
            assertThat(bodies.get(0).at("/owner/uid").asText()).isEqualTo("test-uid");
            assertThat(bodies.get(0).at("/spec/groups/0/features/1/vectorValues/CASH").asBoolean()).isFalse();
            assertThat(bodies.get(2).path("deletionPolicy").asText()).isEqualTo("Retain");
        } finally {
            server.stop(0);
        }
    }
}
