package ru.a1pha1337.featurify.operator;

import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@ControllerConfiguration(finalizerName = "featurify.io/namespace-cleanup")
public final class NamespaceReconciler implements Reconciler<FeaturifyNamespace>, Cleaner<FeaturifyNamespace> {
    private final ManifestClient client;
    private final Duration interval;
    private final Clock clock;

    public NamespaceReconciler(ManifestClient client, Duration interval, Clock clock) {
        this.client = client;
        this.interval = interval;
        this.clock = clock;
    }

    @Override
    public UpdateControl<FeaturifyNamespace> reconcile(FeaturifyNamespace resource, Context<FeaturifyNamespace> context)
            throws InterruptedException {
        NamespaceStatus before = resource.getStatus();
        try {
            client.apply(resource);
            status(resource, true, "Applied", "Desired state applied");
        } catch (IOException exception) {
            failure(resource, exception);
        }
        return (Objects.equals(before, resource.getStatus())
                ? UpdateControl.<FeaturifyNamespace>noUpdate() : UpdateControl.patchStatus(resource)).rescheduleAfter(interval);
    }

    @Override
    public DeleteControl cleanup(FeaturifyNamespace resource, Context<FeaturifyNamespace> context)
            throws InterruptedException {
        try {
            client.cleanup(resource);
            return DeleteControl.defaultDelete();
        } catch (IOException exception) {
            NamespaceStatus before = resource.getStatus();
            failure(resource, exception);
            if (!Objects.equals(before, resource.getStatus())) {
                context.getClient().resource(resource).updateStatus();
            }
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(interval);
        }
    }

    private void failure(FeaturifyNamespace resource, IOException exception) {
        String reason = "ServiceUnavailable";
        String message = "Cannot reach Featurify or Keycloak; retrying";
        if (exception instanceof ApiException api) {
            reason = switch (api.statusCode()) {
                case 400, 422 -> "InvalidManifest";
                case 409 -> "Conflict";
                case 401, 403 -> "AuthenticationFailed";
                default -> "ServiceUnavailable";
            };
            message = api.getMessage();
        }
        status(resource, false, reason, message);
    }

    private void status(FeaturifyNamespace resource, boolean ready, String reason, String message) {
        long generation = resource.getMetadata().getGeneration();
        var previous = resource.getStatus();
        var oldCondition = previous == null || previous.conditions() == null ? null
                : previous.conditions().stream().filter(it -> "Ready".equals(it.type())).findFirst().orElse(null);
        String readyValue = ready ? "True" : "False";
        String transition = oldCondition != null && readyValue.equals(oldCondition.status())
                ? oldCondition.lastTransitionTime() : clock.instant().toString();
        Long observed = ready ? Long.valueOf(generation) : previous == null ? null : previous.observedGeneration();
        resource.setStatus(new NamespaceStatus(observed, List.of(
                new NamespaceStatus.Condition("Ready", readyValue, reason, message, generation, transition))));
    }
}
