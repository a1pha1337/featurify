package ru.a1pha1337.featurify.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeaturifyOperatorProperties.class)
public class FeaturifyOperatorConfiguration {
    @Bean
    HttpClient manifestHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    ManifestClient manifestClient(HttpClient http, FeaturifyOperatorProperties properties) {
        var mapper = new ObjectMapper();
        var tokens = new OAuthTokenProvider(http, properties.tokenUrl(), properties.clientId(),
                properties.clientSecretFile(), mapper);
        return new HttpManifestClient(http, properties.url(), properties.clusterId(), tokens, mapper);
    }

    @Bean
    NamespaceReconciler namespaceReconciler(ManifestClient client, FeaturifyOperatorProperties properties) {
        return new NamespaceReconciler(client, Duration.ofSeconds(properties.reconcileIntervalSeconds()), Clock.systemUTC());
    }
}
