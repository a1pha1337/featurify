package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.javaoperatorsdk.operator.Operator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@EnableKubernetesMockClient(crud = true, https = false)
class OperatorApplicationTests {
    KubernetesClient client;
    KubernetesMockServer server;
    @TempDir Path directory;

    @Test
    void mainFailsWhenKubernetesRejectsTheWatch() {
        server.expect().get()
                .withPath("/apis/featurify.io/v1alpha1/namespaces/applications/featurifynamespaces?resourceVersion=0")
                .andReturn(403, new StatusBuilder().withStatus("Failure").withReason("Forbidden").withCode(403).build())
                .always();
        assertThatThrownBy(() -> OperatorMain.main(new String[] {
                "--featurify.operator.watch-namespace=applications", "--featurify.operator.cluster-id=test-cluster",
                "--featurify.operator.url=http://127.0.0.1:1", "--featurify.operator.token-url=http://127.0.0.1:1/token",
                "--featurify.operator.client-id=operator", "--featurify.operator.client-secret-file=unused-test-secret",
                "--javaoperatorsdk.client.master-url=" + server.url("/"), "--javaoperatorsdk.cache-sync-timeout=1s"
        })).isInstanceOf(IllegalStateException.class).hasMessage("Kubernetes operator failed to start");
    }

    @Test
    void startsReconcilesOnlyWatchedNamespaceAndStopsWithSpring() throws Exception {
        server.expectCustomResource(CustomResourceDefinitionContext.fromCustomResourceType(FeaturifyNamespace.class));
        var mapper = new ObjectMapper();
        var manifests = new CopyOnWriteArrayList<JsonNode>();
        var cleanups = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"test-token\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.createContext("/api/v1/operator/namespaces/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/cleanup")) {
                cleanups.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            } else {
                manifests.add(mapper.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        http.start();
        try {
            Path secret = directory.resolve("client-secret");
            Files.writeString(secret, "test-secret");
            String baseUrl = "http://127.0.0.1:" + http.getAddress().getPort();
            var environment = new HashMap<String, Object>();
            environment.put("WATCH_NAMESPACE", "applications");
            environment.put("FEATURIFY_CLUSTER_ID", "test-cluster");
            environment.put("FEATURIFY_URL", baseUrl);
            environment.put("KEYCLOAK_TOKEN_URL", baseUrl + "/token");
            environment.put("KEYCLOAK_CLIENT_ID", "operator");
            environment.put("KEYCLOAK_CLIENT_SECRET_FILE", secret.toString());
            var application = new SpringApplication(OperatorMain.class);
            application.setRegisterShutdownHook(false);
            application.addInitializers(context -> {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-environment", environment));
                ((GenericApplicationContext) context).registerBean("testKubernetesConfig", Config.class, client::getConfiguration);
            });

            KubernetesClient operatorClient;
            try (var context = application.run()) {
                assertThat(context.isActive()).isTrue();
                var operator = context.getBean(Operator.class);
                operatorClient = operator.getKubernetesClient();
                assertThat(operator.getRegisteredControllers()).hasSize(1);
                var configuration = operator.getRegisteredController("featurify-namespace").orElseThrow().getConfiguration();
                assertThat(configuration.getEffectiveNamespaces()).containsExactly("applications");
                assertThat(configuration.getFinalizerName()).isEqualTo("featurify.io/namespace-cleanup");
                assertThat(operator.getConfigurationService().useSSAToPatchPrimaryResource()).isFalse();
                assertThat(operator.getConfigurationService().getLeaderElectionConfiguration()).isEmpty();
                assertThat(context.getEnvironment().getProperty("spring.main.keep-alive", Boolean.class)).isTrue();
                assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");

                var foreign = NamespaceReconcilerTests.resource();
                foreign.getMetadata().setNamespace("other");
                client.resource(foreign).create();
                var watched = NamespaceReconcilerTests.resource();
                client.resource(watched).create();
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    var updated = client.resources(FeaturifyNamespace.class).inNamespace("applications").withName("checkout").get();
                    assertThat(updated.getStatus()).isNotNull();
                    assertThat(updated.getStatus().conditions().getFirst().status()).isEqualTo("True");
                    assertThat(updated.getMetadata().getFinalizers()).containsExactly("featurify.io/namespace-cleanup");
                });
                assertThat(manifests).isNotEmpty().allSatisfy(manifest -> {
                    assertThat(manifest.path("owner").path("kubernetesNamespace").asText()).isEqualTo("applications");
                    assertThat(manifest.path("owner").path("clusterId").asText()).isEqualTo("test-cluster");
                });
                assertThat(client.resource(foreign).get().getStatus()).isNull();
                assertThat(client.resource(foreign).get().getMetadata().getFinalizers()).isEmpty();

                client.resource(watched).delete();
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(cleanups.get()).isPositive();
                    assertThat(client.resource(watched).get()).isNull();
                });
            }
            assertThat(operatorClient.getHttpClient().isClosed()).isTrue();
        } finally {
            http.stop(0);
        }
    }
}
