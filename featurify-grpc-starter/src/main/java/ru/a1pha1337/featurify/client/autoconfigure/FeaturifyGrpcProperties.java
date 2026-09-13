package ru.a1pha1337.featurify.client.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * JavaBean binding works across Boot 2, 3 and 4, without Kotlin reflection or Jakarta validation.
 */
@Getter
@Setter
@ConfigurationProperties("featurify.grpc")
public class FeaturifyGrpcProperties {
    private boolean enabled = true;
    private String host = "localhost";
    private int port = 9090;
    private String token = "";
    private String defaultGroup;
    private Duration timeout = Duration.ofSeconds(2);
    private boolean tls = true;
    private Resource trustCertificate;
    private final Cache cache = new Cache();

    @Getter
    @Setter
    public static class Cache {
        private Duration ttl = Duration.ofSeconds(1);
        private long maximumSize = 10_000;
    }

    // Deliberately no toString containing credentials.
}
