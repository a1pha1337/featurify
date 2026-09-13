package ru.a1pha1337.featurify.client.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/** JavaBean binding works across Boot 2 and 3, without Kotlin reflection or Jakarta validation. */
@ConfigurationProperties("featurify.grpc")
public class FeaturifyGrpcProperties {
    private boolean enabled = true;
    private String host = "localhost";
    private int port = 9090;
    private String token = "";
    private Duration timeout = Duration.ofSeconds(2);
    private boolean tls = true;
    private Resource trustCertificate;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public boolean isTls() { return tls; }
    public void setTls(boolean tls) { this.tls = tls; }
    public Resource getTrustCertificate() { return trustCertificate; }
    public void setTrustCertificate(Resource trustCertificate) { this.trustCertificate = trustCertificate; }

    // Deliberately no toString containing credentials.
}
