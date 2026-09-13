package ru.a1pha1337.featurify.client;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc;
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest;
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Owns the supplied channel. Close the client when it is no longer needed. */
public final class GrpcFeaturifyClient implements FeaturifyClient, AutoCloseable {
    private final ManagedChannel channel;
    private final long timeoutNanos;
    private final FeatureServiceGrpc.FeatureServiceBlockingStub stub;

    public GrpcFeaturifyClient(ManagedChannel channel, String token) {
        this(channel, token, Duration.ofSeconds(2));
    }

    public GrpcFeaturifyClient(ManagedChannel channel, String token, Duration timeout) {
        validateCredentialsAndTimeout(token, timeout);
        this.channel = Objects.requireNonNull(channel, "channel");
        this.timeoutNanos = timeout.toNanos();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        this.stub = FeatureServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    /** Creates a client without Spring. The caller owns the optional PEM certificate stream. */
    public static GrpcFeaturifyClient connect(
            String host, int port, String token, Duration timeout, boolean tls, InputStream trustCertificate
    ) throws SSLException {
        validateCredentialsAndTimeout(token, timeout);
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Featurify host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Featurify port must be between 1 and 65535");
        }
        if (!tls && trustCertificate != null) {
            throw new IllegalArgumentException("Featurify trust certificate requires TLS");
        }
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port).disableRetry();
        if (tls) {
            SslContextBuilder ssl = GrpcSslContexts.forClient();
            if (trustCertificate != null) {
                ssl.trustManager(trustCertificate);
            }
            builder.sslContext(ssl.build());
        } else {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();
        try {
            return new GrpcFeaturifyClient(channel, token, timeout);
        } catch (RuntimeException exception) {
            channel.shutdownNow();
            throw exception;
        }
    }

    private static void validateCredentialsAndTimeout(String token, Duration timeout) {
        if (token == null || token.isEmpty() || token.chars().anyMatch(c -> c < '!' || c > '~')) {
            throw new IllegalArgumentException("Featurify token must be non-empty printable ASCII without spaces");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Featurify timeout must be positive and at most one day");
        }
    }

    @Override
    public BooleanFeatureResponse getBooleanFeature(String key, String group) {
        return stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getBooleanFeature(request(key, group));
    }

    @Override
    public EnumFeatureResponse getEnumFeature(String key, String group) {
        return stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getEnumFeature(request(key, group));
    }

    @Override
    public BooleanFeatureResponse getVectorFeature(String key, String element, String group) {
        return stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getVectorFeature(
                GetVectorFeatureRequest.newBuilder().setKey(key).setElement(element)
                        .setGroup(group == null ? "" : group).build());
    }

    private GetFeatureRequest request(String key, String group) {
        return GetFeatureRequest.newBuilder().setKey(key).setGroup(group == null ? "" : group).build();
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
