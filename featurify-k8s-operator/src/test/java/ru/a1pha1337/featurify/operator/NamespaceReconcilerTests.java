package ru.a1pha1337.featurify.operator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class NamespaceReconcilerTests {
    private final ManifestClient client = mock(ManifestClient.class);
    private final NamespaceReconciler reconciler = new NamespaceReconciler(client, Duration.ofSeconds(30),
            Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC));

    static FeaturifyNamespace resource() throws IOException {
        var resource = Serialization.unmarshal(Files.readString(Path.of("examples/checkout.yaml")), FeaturifyNamespace.class);
        resource.setMetadata(new ObjectMetaBuilder(resource.getMetadata()).withUid("test-uid").withGeneration(1L).build());
        return resource;
    }

    @Test
    void exampleDeserializesTypedValuesAndRoundTrips() throws Exception {
        var resource = resource();
        assertThat(resource.getSpec().features().getFirst().booleanValue()).isFalse();
        assertThat(resource.getSpec().features().get(1).type()).isEqualTo(NamespaceSpec.FeatureType.PAYLOAD);
        assertThat(resource.getSpec().features().get(1).payloadValue()).contains("\"theme\":{\"accent\":\"blue\"}");
        assertThat(resource.getSpec().groups().getFirst().features().get(1).vectorValues()).containsEntry("CASH", false);
        assertThat(Serialization.unmarshal(Serialization.asJson(resource), FeaturifyNamespace.class).getSpec()).isEqualTo(resource.getSpec());
    }

    @Test
    void successfulReconcileUpdatesStatusOnceAndAlwaysSchedulesDriftCheck() throws Exception {
        var resource = resource();
        var first = reconciler.reconcile(resource, null);
        assertThat(first.isPatchStatus()).isTrue();
        assertThat(resource.getStatus().observedGeneration()).isEqualTo(1);
        assertThat(resource.getStatus().conditions().getFirst().status()).isEqualTo("True");
        var next = reconciler.reconcile(resource, null);
        assertThat(next.isPatchStatus()).isFalse();
        assertThat(next.getScheduleDelay()).contains(30_000L);
    }

    @Test
    void failedGenerationDoesNotAdvanceLastAppliedGeneration() throws Exception {
        var resource = resource();
        reconciler.reconcile(resource, null);
        resource.getMetadata().setGeneration(2L);
        doThrow(new ApiException(409, "Current ENUM value was removed from enumOptions")).when(client).apply(resource);
        reconciler.reconcile(resource, null);
        assertThat(resource.getStatus().observedGeneration()).isEqualTo(1);
        var condition = resource.getStatus().conditions().getFirst();
        assertThat(condition.observedGeneration()).isEqualTo(2);
        assertThat(condition.reason()).isEqualTo("Conflict");
        assertThat(condition.status()).isEqualTo("False");
    }

    @Test
    void networkErrorsDoNotLeakExceptionContents() throws Exception {
        var resource = resource();
        doThrow(new IOException("secret-content")).when(client).apply(resource);
        reconciler.reconcile(resource, null);
        assertThat(resource.getStatus().observedGeneration()).isNull();
        assertThat(resource.getStatus().conditions().getFirst().message()).doesNotContain("secret-content");
    }

    @Test
    void cleanupKeepsFinalizerUntilRemoteReleaseSucceeds() throws Exception {
        var resource = resource();
        Context<FeaturifyNamespace> context = mock(Context.class, RETURNS_DEEP_STUBS);
        doThrow(new ApiException(503, "Service unavailable")).doNothing().when(client).cleanup(resource);
        assertThat(reconciler.cleanup(resource, context).isRemoveFinalizer()).isFalse();
        assertThat(resource.getStatus().conditions().getFirst().status()).isEqualTo("False");
        assertThat(reconciler.cleanup(resource, context).isRemoveFinalizer()).isTrue();
    }
}
