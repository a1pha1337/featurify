package ru.a1pha1337.featurify.client.autoconfigure;

import io.grpc.ManagedChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.client.GrpcFeaturifyClient;
import ru.a1pha1337.featurify.client.service.BooleanFeatureService;
import ru.a1pha1337.featurify.client.service.EnumFeatureService;
import ru.a1pha1337.featurify.client.service.PayloadFeatureService;
import ru.a1pha1337.featurify.client.service.VectorFeatureService;

import java.io.IOException;
import java.io.InputStream;

// No @AutoConfiguration or proxyBeanMethods: these are absent in early Boot 2 / Spring 5.
@Configuration
@ConditionalOnClass(ManagedChannel.class)
@ConditionalOnProperty(prefix = "featurify.grpc", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FeaturifyGrpcProperties.class)
public class FeaturifyGrpcAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(FeaturifyClient.class)
    public GrpcFeaturifyClient featurifyClient(FeaturifyGrpcProperties properties) throws IOException {
        if (!properties.isTls() && properties.getTrustCertificate() != null) {
            throw new IllegalArgumentException("featurify.grpc.trust-certificate requires TLS");
        }
        try (InputStream certificate = properties.getTrustCertificate() == null
                ? null : properties.getTrustCertificate().getInputStream()) {
            return GrpcFeaturifyClient.connect(properties.getHost(), properties.getPort(), properties.getToken(),
                    properties.getTimeout(), properties.isTls(), certificate);
        }
    }

    @Bean
    @ConditionalOnMissingBean(BooleanFeatureService.class)
    public BooleanFeatureService booleanFeatureService(FeaturifyClient client, FeaturifyGrpcProperties properties) {
        return new BooleanFeatureService(client, properties.getCache().getTtl(), properties.getCache().getMaximumSize(),
                properties.getDefaultGroup());
    }

    @Bean
    @ConditionalOnMissingBean(EnumFeatureService.class)
    public EnumFeatureService enumFeatureService(FeaturifyClient client, FeaturifyGrpcProperties properties) {
        return new EnumFeatureService(client, properties.getCache().getTtl(), properties.getCache().getMaximumSize(),
                properties.getDefaultGroup());
    }

    @Bean
    @ConditionalOnMissingBean(PayloadFeatureService.class)
    public PayloadFeatureService payloadFeatureService(FeaturifyClient client, FeaturifyGrpcProperties properties) {
        return new PayloadFeatureService(client, properties.getCache().getTtl(), properties.getCache().getMaximumSize(),
                properties.getDefaultGroup());
    }

    @Bean
    @ConditionalOnMissingBean(VectorFeatureService.class)
    public VectorFeatureService vectorFeatureService(FeaturifyClient client, FeaturifyGrpcProperties properties) {
        return new VectorFeatureService(client, properties.getCache().getTtl(), properties.getCache().getMaximumSize(),
                properties.getDefaultGroup());
    }
}
