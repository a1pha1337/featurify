package compatibility;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcProperties;
import ru.a1pha1337.featurify.client.service.BooleanFeatureService;
import ru.a1pha1337.featurify.client.service.EnumFeatureService;
import ru.a1pha1337.featurify.client.service.VectorFeatureService;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc;
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest;
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs on actual Java 8 as well as modern JDKs using only published Maven artifacts.
 */
public class CompatibilitySmoke {
    @Configuration
    @EnableAutoConfiguration
    public static class Application {
    }

    @Configuration
    public static class CustomClient {
        @Bean
        public FeaturifyClient customClient() {
            return new FeaturifyClient() {
                public BooleanFeatureResponse getBooleanFeature(String key, String group) {
                    return BooleanFeatureResponse.newBuilder().setValue(true).build();
                }

                public EnumFeatureResponse getEnumFeature(String key, String group) {
                    return EnumFeatureResponse.getDefaultInstance();
                }

                public BooleanFeatureResponse getVectorFeature(String key, String element, String group) {
                    return BooleanFeatureResponse.getDefaultInstance();
                }
            };
        }
    }

    @Configuration
    public static class CustomServices {
        @Bean
        public BooleanFeatureService customBooleanService(FeaturifyClient client) {
            return new BooleanFeatureService(client, Duration.ofSeconds(1), 100);
        }

        @Bean
        public EnumFeatureService customEnumService(FeaturifyClient client) {
            return new EnumFeatureService(client, Duration.ofSeconds(1), 100);
        }

        @Bean
        public VectorFeatureService customVectorService(FeaturifyClient client) {
            return new VectorFeatureService(client, Duration.ofSeconds(1), 100);
        }
    }

    public static void main(String[] args) throws Exception {
        check(args[0].equals(SpringBootVersion.getVersion()), "Wrong Boot runtime");
        for (String forbidden : new String[]{"kotlin.Unit", "jakarta.validation.Validation",
                "ru.a1pha1337.featurify.dto.FeatureResponse", "ru.a1pha1337.featurify.FeaturifyApplication"}) {
            try {
                Class.forName(forbidden);
                throw new AssertionError("Unexpected client dependency: " + forbidden);
            } catch (ClassNotFoundException expected) {
                // Published clients are independent of the server and Kotlin/Jakarta.
            }
        }
        try (AnnotationConfigApplicationContext disabled = context(properties("featurify.grpc.enabled", "false"), false)) {
            check(disabled.getBeansOfType(FeaturifyClient.class).isEmpty(), "Disabled starter created a client");
            check(disabled.getBeansOfType(BooleanFeatureService.class).isEmpty(), "Disabled boolean service created");
            check(disabled.getBeansOfType(EnumFeatureService.class).isEmpty(), "Disabled enum service created");
            check(disabled.getBeansOfType(VectorFeatureService.class).isEmpty(), "Disabled vector service created");
        }
        try (AnnotationConfigApplicationContext custom = context(new HashMap<>(), true)) {
            check(custom.getBeansOfType(FeaturifyClient.class).size() == 1, "Override created duplicate clients");
            check(custom.getBean(FeaturifyClient.class).getBooleanFeature("enabled").getValue(), "Override ignored");
            check(custom.getBean(BooleanFeatureService.class).isEnabled("enabled"), "Services ignored custom client");
            check(custom.getBeansOfType(EnumFeatureService.class).size() == 1, "Missing enum service with custom client");
            check(custom.getBeansOfType(VectorFeatureService.class).size() == 1, "Missing vector service with custom client");
            check(custom.getBean(FeaturifyGrpcProperties.class).getCache().getTtl().equals(Duration.ofSeconds(1)), "Wrong default TTL");
            check(custom.getBean(FeaturifyGrpcProperties.class).getDefaultGroup() == null, "Default group must be null");
        }
        try (AnnotationConfigApplicationContext custom = context(new HashMap<>(), true, CustomServices.class)) {
            check(custom.getBeansOfType(BooleanFeatureService.class).size() == 1, "Duplicate boolean service");
            check(custom.getBeansOfType(EnumFeatureService.class).size() == 1, "Duplicate enum service");
            check(custom.getBeansOfType(VectorFeatureService.class).size() == 1, "Duplicate vector service");
            check(custom.getBean(BooleanFeatureService.class) == custom.getBean("customBooleanService"), "Boolean override ignored");
            check(custom.getBean(EnumFeatureService.class) == custom.getBean("customEnumService"), "Enum override ignored");
            check(custom.getBean(VectorFeatureService.class) == custom.getBean("customVectorService"), "Vector override ignored");
        }
        expectStartupFailure(new HashMap<>());
        Map<String, Object> invalid = properties("featurify.grpc.token", "test-token");
        invalid.put("featurify.grpc.timeout", "0s");
        expectStartupFailure(invalid);
        invalid.remove("featurify.grpc.timeout");
        invalid.put("featurify.grpc.cache.ttl", "-1s");
        expectStartupFailure(invalid);
        invalid.put("featurify.grpc.cache.ttl", "1s");
        invalid.put("featurify.grpc.cache.maximum-size", "0");
        expectStartupFailure(invalid);
        verifyTransport(false);
        verifyTransport(true);
        System.out.println("PASS Boot " + args[0] + " / Java " + System.getProperty("java.version")
                + ": discovery, binding, overrides, disable, validation, cached services, default group, TTL, authenticated RPCs, TLS, deadline and shutdown");
    }

    private static void verifyTransport(boolean tls) throws Exception {
        AtomicInteger authenticatedCalls = new AtomicInteger();
        NettyServerBuilder builder = NettyServerBuilder.forPort(0).intercept(new ServerInterceptor() {
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (!"Bearer test-token".equals(headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<ReqT>() {
                    };
                }
                authenticatedCalls.incrementAndGet();
                return next.startCall(call, headers);
            }
        }).addService(new FeatureServiceGrpc.FeatureServiceImplBase() {
            public void getBooleanFeature(GetFeatureRequest request, StreamObserver<BooleanFeatureResponse> observer) {
                if ("slow".equals(request.getKey())) return;
                observer.onNext(BooleanFeatureResponse.newBuilder().setValue(false).setVersion(42).build());
                observer.onCompleted();
            }

            public void getEnumFeature(GetFeatureRequest request, StreamObserver<EnumFeatureResponse> observer) {
                observer.onNext(EnumFeatureResponse.newBuilder().setValue(request.getGroup()).setVersion(43).build());
                observer.onCompleted();
            }

            public void getVectorFeature(GetVectorFeatureRequest request, StreamObserver<BooleanFeatureResponse> observer) {
                observer.onNext(BooleanFeatureResponse.newBuilder().setValue("DOG".equals(request.getElement())).setVersion(44).build());
                observer.onCompleted();
            }
        });
        if (tls) {
            try (InputStream cert = CompatibilitySmoke.class.getResourceAsStream("/grpc-tls/server.crt");
                 InputStream key = CompatibilitySmoke.class.getResourceAsStream("/grpc-tls/server.key")) {
                builder.useTransportSecurity(cert, key);
            }
        }
        Server server = builder.build().start();
        try {
            Map<String, Object> settings = properties("featurify.grpc.token", "test-token");
            settings.put("featurify.grpc.port", server.getPort());
            settings.put("featurify.grpc.timeout", "2s");
            settings.put("featurify.grpc.cache.ttl", "1s");
            settings.put("featurify.grpc.cache.maximum-size", "25");
            if (tls) settings.put("featurify.grpc.trust-certificate", "classpath:grpc-tls/server.crt");
            else settings.put("featurify.grpc.tls", "false");
            FeaturifyClient client;
            try (AnnotationConfigApplicationContext context = context(settings, false)) {
                check(context.getBeansOfType(FeaturifyClient.class).size() == 1, "Missing or duplicate auto-configuration");
                check(context.getBean(FeaturifyGrpcProperties.class).getTimeout().equals(Duration.ofSeconds(2)), "Duration binding failed");
                check(context.getBean(FeaturifyGrpcProperties.class).getCache().getTtl().equals(Duration.ofSeconds(1)), "Cache TTL binding failed");
                check(context.getBean(FeaturifyGrpcProperties.class).getCache().getMaximumSize() == 25, "Cache size binding failed");
                client = context.getBean(FeaturifyClient.class);
                check(client.getBooleanFeature("enabled").getVersion() == 42, "Boolean response lost version");
                check("checkout".equals(client.getEnumFeature("color", "checkout").getValue()), "Group lost");
                check(client.getVectorFeature("animals", "DOG").getValue(), "Vector request failed");
                try {
                    client.getBooleanFeature("slow");
                    throw new AssertionError("Deadline not enforced");
                } catch (StatusRuntimeException error) {
                    check(error.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED, "Wrong timeout status");
                }
                check(client.getBooleanFeature("enabled").getVersion() == 42, "Deadline not renewed");
                verifyServices(context, authenticatedCalls);
            }
            try {
                client.getBooleanFeature("enabled");
                throw new AssertionError("Channel was not closed");
            } catch (StatusRuntimeException error) {
                check(error.getStatus().getCode() == Status.Code.UNAVAILABLE, "Wrong shutdown status");
            }
            check(authenticatedCalls.get() == 16, "Authentication or unexpected retries: " + authenticatedCalls.get());
            settings.put("featurify.grpc.default-group", "checkout");
            try (AnnotationConfigApplicationContext context = context(settings, false)) {
                check("checkout".equals(context.getBean(FeaturifyGrpcProperties.class).getDefaultGroup()), "Default group binding failed");
                BooleanFeatureService booleans = context.getBean(BooleanFeatureService.class);
                EnumFeatureService enums = context.getBean(EnumFeatureService.class);
                VectorFeatureService vectors = context.getBean(VectorFeatureService.class);
                booleans.isEnabled("grouped");
                check(booleans.getFeature("grouped") == booleans.getFeature("grouped", "checkout"), "Boolean default group ignored");
                check("checkout".equals(enums.getValue("grouped")), "Enum default group ignored");
                check(enums.getFeature("grouped") == enums.getFeature("grouped", "checkout"), "Enum default group cache mismatch");
                check("other".equals(enums.getValue("grouped", "other")), "Explicit group ignored");
                check("".equals(enums.getValue("grouped", null)), "Explicit null must select Global");
                check(enums.getFeature("grouped", null) == enums.getFeature("grouped", ""), "Global group cache mismatch");
                vectors.isEnabled("grouped", "DOG");
                check(vectors.getFeature("grouped", "DOG") == vectors.getFeature("grouped", "DOG", "checkout"), "Vector default group ignored");
                check(authenticatedCalls.get() == 21, "Default group cache keys differ from explicit group");
            }
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void verifyServices(AnnotationConfigApplicationContext context, AtomicInteger calls) throws Exception {
        int before = calls.get();
        BooleanFeatureService booleans = context.getBean(BooleanFeatureService.class);
        EnumFeatureService enums = context.getBean(EnumFeatureService.class);
        VectorFeatureService vectors = context.getBean(VectorFeatureService.class);
        for (int i = 0; i < 2; i++) {
            check(!booleans.isEnabled("shared"), "False response lost");
            check(booleans.getFeature("shared", "").getVersion() == 42, "Boolean version lost");
            check("".equals(enums.getValue("shared")), "Empty enum lost");
            check(enums.getFeature("shared", "").getVersion() == 43, "Enum version lost");
            check(vectors.isEnabled("shared", "DOG"), "Vector value lost");
            check(vectors.getFeature("shared", "DOG", "").getVersion() == 44, "Vector version lost");
        }
        check(calls.get() == before + 3, "Repeated calls were not cached");
        booleans.isEnabled("shared", "checkout");
        check("checkout".equals(enums.getValue("shared", "checkout")), "Enum group collided");
        check(!vectors.isEnabled("shared", "CAT"), "Vector element collided");
        vectors.isEnabled("shared", "DOG", "checkout");
        booleans.isEnabled("other");
        check(calls.get() == before + 8, "Cache keys collided");
        Thread.sleep(1100);
        booleans.isEnabled("shared");
        enums.getValue("shared");
        vectors.isEnabled("shared", "DOG");
        check(calls.get() == before + 11, "Expired responses were not reloaded");
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> settings, boolean custom, Class<?>... extra) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", settings));
        if (custom) context.register(CustomClient.class);
        if (extra.length > 0) context.register(extra);
        context.register(Application.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    private static void expectStartupFailure(Map<String, Object> settings) {
        try (AnnotationConfigApplicationContext ignored = context(settings, false)) {
            throw new AssertionError("Invalid configuration accepted");
        } catch (org.springframework.beans.BeansException expected) {
            // Invalid configuration must fail at startup on every supported version.
        }
    }

    private static Map<String, Object> properties(String key, String value) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(key, value);
        return properties;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
