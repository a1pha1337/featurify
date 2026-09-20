package ru.a1pha1337.featurify.client.service;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.PayloadFeatureResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFeatureServiceTests {
    @Test
    void cachesResponsesByGroupExpiresFromLoadAndDoesNotCacheFailures() {
        AtomicLong clock = new AtomicLong();
        AtomicLong calls = new AtomicLong();
        FeaturifyClient client = new FeaturifyClient() {
            public BooleanFeatureResponse getBooleanFeature(String key, String group) { throw new UnsupportedOperationException(); }
            public EnumFeatureResponse getEnumFeature(String key, String group) { throw new UnsupportedOperationException(); }
            public BooleanFeatureResponse getVectorFeature(String key, String element, String group) { throw new UnsupportedOperationException(); }
            public PayloadFeatureResponse getPayloadFeature(String key, String group) {
                long version = calls.incrementAndGet();
                if (key.equals("missing")) throw Status.NOT_FOUND.asRuntimeException();
                return PayloadFeatureResponse.newBuilder().setValue("{\"group\":\"" + group + "\"}").setVersion(version).build();
            }
        };
        PayloadFeatureService service = new PayloadFeatureService(client, Duration.ofSeconds(1), 100, "checkout", clock::get);
        PayloadFeatureResponse first = service.getFeature("config");
        assertThat(first.getValue()).isEqualTo("{\"group\":\"checkout\"}");
        assertThat(service.getFeature("config", "checkout")).isSameAs(first);
        assertThat(service.getFeature("config", null)).isSameAs(service.getFeature("config", ""));
        clock.set(Duration.ofMillis(900).toNanos());
        assertThat(service.getFeature("config")).isSameAs(first);
        clock.set(Duration.ofSeconds(1).toNanos());
        assertThat(service.getFeature("config").getVersion()).isGreaterThan(first.getVersion());
        long beforeFailures = calls.get();
        assertThatThrownBy(() -> service.getFeature("missing")).isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThatThrownBy(() -> service.getFeature("missing")).isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(calls.get()).isEqualTo(beforeFailures + 2);
        PayloadFeatureService uncached = new PayloadFeatureService(client, Duration.ZERO, 100);
        assertThat(uncached.getFeature("config").getVersion()).isNotEqualTo(uncached.getFeature("config").getVersion());
    }
}
