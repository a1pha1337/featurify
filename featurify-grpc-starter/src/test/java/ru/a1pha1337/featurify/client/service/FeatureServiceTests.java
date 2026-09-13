package ru.a1pha1337.featurify.client.service;

import com.github.benmanes.caffeine.cache.Cache;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcAutoConfiguration;
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcProperties;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureServiceTests {
    private final AtomicLong clock = new AtomicLong();
    private final StubClient client = new StubClient();
    private final BooleanFeatureService booleans = new BooleanFeatureService(client,
            Duration.ofSeconds(1), 100, null, clock::get);
    private final EnumFeatureService enums = new EnumFeatureService(client,
            Duration.ofSeconds(1), 100, null, clock::get);
    private final VectorFeatureService vectors = new VectorFeatureService(client,
            Duration.ofSeconds(1), 100, null, clock::get);

    @Test
    void configuredDefaultGroupIsUsedByAllServicesAndSharesExplicitGroupCacheEntries() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeaturifyGrpcAutoConfiguration.class))
                .withBean(FeaturifyClient.class, () -> client)
                .withPropertyValues("featurify.grpc.default-group=checkout", "featurify.grpc.cache.ttl=1h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FeaturifyGrpcProperties.class).getDefaultGroup()).isEqualTo("checkout");
                    BooleanFeatureService booleans = context.getBean(BooleanFeatureService.class);
                    EnumFeatureService enums = context.getBean(EnumFeatureService.class);
                    VectorFeatureService vectors = context.getBean(VectorFeatureService.class);

                    booleans.isEnabled("flag");
                    assertThat(enums.getValue("flag")).isEqualTo("checkout");
                    vectors.isEnabled("flag", "DOG");
                    assertThat(booleans.getFeature("flag")).isSameAs(booleans.getFeature("flag", "checkout"));
                    assertThat(enums.getFeature("flag")).isSameAs(enums.getFeature("flag", "checkout"));
                    assertThat(vectors.getFeature("flag", "DOG")).isSameAs(vectors.getFeature("flag", "DOG", "checkout"));
                    assertThat(client.calls.get()).isEqualTo(3);

                    booleans.isEnabled("flag", "other");
                    assertThat(enums.getValue("flag", "other")).isEqualTo("other");
                    vectors.isEnabled("flag", "DOG", "other");
                    booleans.isEnabled("flag", null);
                    assertThat(enums.getValue("flag", null)).isEmpty();
                    vectors.isEnabled("flag", "DOG", null);
                    assertThat(booleans.getFeature("flag", "")).isSameAs(booleans.getFeature("flag", null));
                    assertThat(enums.getFeature("flag", "")).isSameAs(enums.getFeature("flag", null));
                    assertThat(vectors.getFeature("flag", "DOG", "")).isSameAs(vectors.getFeature("flag", "DOG", null));
                    assertThat(client.requests).containsExactly(
                            "boolean:flag:checkout", "enum:flag:checkout", "vector:flag:DOG:checkout",
                            "boolean:flag:other", "enum:flag:other", "vector:flag:DOG:other",
                            "boolean:flag:", "enum:flag:", "vector:flag:DOG:");
                });
    }

    @Test
    void unsetOrEmptyDefaultGroupUsesGlobal() {
        for (String[] properties : new String[][]{{}, {"featurify.grpc.default-group="}}) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(FeaturifyGrpcAutoConfiguration.class))
                    .withBean(FeaturifyClient.class, () -> client)
                    .withPropertyValues(properties)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        String defaultGroup = context.getBean(FeaturifyGrpcProperties.class).getDefaultGroup();
                        if (properties.length == 0) assertThat(defaultGroup).isNull();
                        else assertThat(defaultGroup).isEmpty();
                        context.getBean(BooleanFeatureService.class).isEnabled("flag");
                        assertThat(context.getBean(EnumFeatureService.class).getValue("flag")).isEmpty();
                        context.getBean(VectorFeatureService.class).isEnabled("flag", "DOG");
                    });
        }
        assertThat(client.requests).containsExactly(
                "boolean:flag:", "enum:flag:", "vector:flag:DOG:",
                "boolean:flag:", "enum:flag:", "vector:flag:DOG:");
    }

    @Test
    void successfulResponsesExpireAfterWriteWithoutExtendingTtlOnRead() {
        assertThat(booleans.isEnabled("flag")).isFalse();
        assertThat(enums.getValue("flag")).isEmpty();
        assertThat(vectors.isEnabled("flag", "DOG")).isFalse();
        clock.set(TimeUnit.MILLISECONDS.toNanos(999));
        assertThat(booleans.getFeature("flag").getVersion()).isEqualTo(1);
        assertThat(enums.getFeature("flag").getVersion()).isEqualTo(2);
        assertThat(vectors.getFeature("flag", "DOG").getVersion()).isEqualTo(3);
        assertThat(client.calls.get()).isEqualTo(3);
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertThat(booleans.getFeature("flag").getVersion()).isEqualTo(4);
        assertThat(enums.getFeature("flag").getVersion()).isEqualTo(5);
        assertThat(vectors.getFeature("flag", "DOG").getVersion()).isEqualTo(6);
    }

    @Test
    void keysIncludeFeatureGroupAndVectorElementAndNormalizeGlobalGroup() {
        assertThat(booleans.getFeature("flag", "")).isSameAs(booleans.getFeature("flag"));
        assertThat(enums.getFeature("flag", "")).isSameAs(enums.getFeature("flag"));
        assertThat(vectors.getFeature("flag", "DOG", "")).isSameAs(vectors.getFeature("flag", "DOG"));
        assertThat(client.calls.get()).isEqualTo(3);
        booleans.isEnabled("flag", "checkout");
        booleans.isEnabled("other", "checkout");
        assertThat(enums.getValue("flag", "checkout")).isEqualTo("checkout");
        enums.getValue("other", "checkout");
        assertThat(vectors.isEnabled("flag", "CAT")).isTrue();
        assertThat(vectors.isEnabled("flag", "DOG", "checkout")).isFalse();
        vectors.isEnabled("other", "DOG", "checkout");
        assertThat(client.calls.get()).isEqualTo(10);
        assertThat(client.requests).containsExactly(
                "boolean:flag:", "enum:flag:", "vector:flag:DOG:",
                "boolean:flag:checkout", "boolean:other:checkout", "enum:flag:checkout",
                "enum:other:checkout", "vector:flag:CAT:", "vector:flag:DOG:checkout", "vector:other:DOG:checkout");
    }

    @Test
    void rpcErrorsKeepStatusAndTrailersAndAreNotCached() {
        Metadata trailers = new Metadata();
        trailers.put(Metadata.Key.of("error-details", Metadata.ASCII_STRING_MARSHALLER), "details");
        StatusRuntimeException error = Status.UNAVAILABLE.withDescription("offline").asRuntimeException(trailers);
        for (Runnable request : Arrays.<Runnable>asList(
                () -> booleans.isEnabled("flag"), () -> enums.getValue("flag"), () -> vectors.isEnabled("flag", "DOG"))) {
            client.failure = error;
            assertThatThrownBy(request::run).isSameAs(error);
            assertThatThrownBy(request::run).isSameAs(error);
            client.failure = null;
            request.run();
            request.run();
        }
        assertThat(client.calls.get()).isEqualTo(9);
    }

    @Test
    void zeroTtlDisablesCaching() {
        BooleanFeatureService service = new BooleanFeatureService(client, Duration.ZERO, 100);
        assertThat(service.getFeature("flag").getVersion()).isEqualTo(1);
        assertThat(service.getFeature("flag").getVersion()).isEqualTo(2);
    }

    @Test
    void configuredMaximumSizeBoundsTheCache() {
        Cache<String, String> cache = FeatureCaches.create(Duration.ofSeconds(1), 2, clock::get);
        for (int i = 0; i < 10; i++) cache.put("key" + i, "value");
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void invalidCacheSettingsAreRejected() {
        for (Duration ttl : Arrays.asList(null, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new BooleanFeatureService(client, ttl, 100))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("featurify.grpc.cache.ttl");
        }
        for (long size : new long[]{0, -1}) {
            assertThatThrownBy(() -> new BooleanFeatureService(client, Duration.ofSeconds(1), size))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("featurify.grpc.cache.maximum-size");
        }
    }

    @Test
    void concurrentRequestsForTheSameEntryShareOneLoad() throws Exception {
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StubClient blocking = new StubClient() {
            @Override
            public BooleanFeatureResponse getBooleanFeature(String key, String group) {
                loading.countDown();
                await(release);
                return super.getBooleanFeature(key, group);
            }
        };
        BooleanFeatureService service = new BooleanFeatureService(blocking,
                Duration.ofSeconds(1), 100, null, clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<BooleanFeatureResponse>> responses = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return service.getFeature("flag");
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<BooleanFeatureResponse> response : responses) {
                assertThat(response.get(5, TimeUnit.SECONDS).getVersion()).isEqualTo(1);
            }
            assertThat(blocking.calls.get()).isEqualTo(1);
        } finally {
            start.countDown();
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test gate");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static class StubClient implements FeaturifyClient {
        final AtomicLong calls = new AtomicLong();
        final List<String> requests = new ArrayList<>();
        StatusRuntimeException failure;

        private long record(String request) {
            long version = calls.incrementAndGet();
            requests.add(request);
            if (failure != null) throw failure;
            return version;
        }

        public BooleanFeatureResponse getBooleanFeature(String key, String group) {
            return BooleanFeatureResponse.newBuilder().setValue(false)
                    .setVersion(record("boolean:" + key + ":" + group)).build();
        }

        public EnumFeatureResponse getEnumFeature(String key, String group) {
            return EnumFeatureResponse.newBuilder().setValue(group)
                    .setVersion(record("enum:" + key + ":" + group)).build();
        }

        public BooleanFeatureResponse getVectorFeature(String key, String element, String group) {
            return BooleanFeatureResponse.newBuilder().setValue("CAT".equals(element))
                    .setVersion(record("vector:" + key + ":" + element + ":" + group)).build();
        }
    }
}
