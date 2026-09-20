package ru.a1pha1337.featurify.operator;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class FeaturifyOperatorConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(FeaturifyOperatorConfiguration.class)
            .withPropertyValues(
                    "WATCH_NAMESPACE=applications", "FEATURIFY_CLUSTER_ID=test-cluster",
                    "FEATURIFY_URL=https://featurify.example.com", "KEYCLOAK_TOKEN_URL=https://keycloak.example.com/token",
                    "KEYCLOAK_CLIENT_ID=operator", "KEYCLOAK_CLIENT_SECRET_FILE=/var/run/featurify/client-secret");

    @Test
    void bindsExistingEnvironmentVariablesAndKeepsHttpDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NamespaceReconciler.class);
            var properties = context.getBean(FeaturifyOperatorProperties.class);
            assertThat(properties.watchNamespace()).isEqualTo("applications");
            assertThat(properties.clusterId()).isEqualTo("test-cluster");
            assertThat(properties.clientId()).isEqualTo("operator");
            assertThat(properties.reconcileIntervalSeconds()).isEqualTo(30);
            var http = context.getBean(HttpClient.class);
            assertThat(http.connectTimeout()).contains(Duration.ofSeconds(10));
            assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"WATCH_NAMESPACE", "FEATURIFY_CLUSTER_ID", "FEATURIFY_URL", "KEYCLOAK_TOKEN_URL",
            "KEYCLOAK_CLIENT_ID", "KEYCLOAK_CLIENT_SECRET_FILE"})
    void rejectsMissingRequiredSettings(String key) {
        contextRunner.withPropertyValues(key + "=").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WATCH_NAMESPACE=", "WATCH_NAMESPACE=JOSDK_ALL_NAMESPACES",
            "WATCH_NAMESPACE=applications,other", "WATCH_NAMESPACE=-invalid", "FEATURIFY_CLUSTER_ID=invalid/id",
            "RECONCILE_INTERVAL_SECONDS=4", "RECONCILE_INTERVAL_SECONDS=3601", "RECONCILE_INTERVAL_SECONDS=abc",
            "FEATURIFY_URL=ftp://example.com", "FEATURIFY_URL=https://user:secret@example.com",
            "FEATURIFY_URL=https://example.com?token=secret", "FEATURIFY_URL=https://example.com#fragment",
            "KEYCLOAK_TOKEN_URL=/relative/token", "KEYCLOAK_TOKEN_URL=https://user:secret@example.com/token"})
    void rejectsInvalidSettingsBeforeOperatorStartup(String setting) {
        contextRunner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(longs = {5, 3600})
    void acceptsReconcileIntervalBoundaries(long seconds) {
        contextRunner.withPropertyValues("RECONCILE_INTERVAL_SECONDS=" + seconds).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FeaturifyOperatorProperties.class).reconcileIntervalSeconds()).isEqualTo(seconds);
        });
    }
}
