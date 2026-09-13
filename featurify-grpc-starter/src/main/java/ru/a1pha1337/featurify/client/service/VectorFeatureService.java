package ru.a1pha1337.featurify.client.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest;

import java.time.Duration;
import java.util.Objects;

/**
 * Blocking, thread-safe access to vector membership, with a per-service response cache.
 * TTL is measured from a successful load; reads do not extend it. Zero TTL disables caching.
 * RPC failures propagate unchanged and are never cached.
 * Overloads without a group use the configured default group, or Global when unset.
 */
public class VectorFeatureService {
    private final FeaturifyClient client;
    private final Cache<GetVectorFeatureRequest, BooleanFeatureResponse> cache;
    private final String defaultGroup;

    public VectorFeatureService(FeaturifyClient client, Duration ttl, long maximumSize) {
        this(client, ttl, maximumSize, null);
    }

    public VectorFeatureService(FeaturifyClient client, Duration ttl, long maximumSize, String defaultGroup) {
        this(client, ttl, maximumSize, defaultGroup, Ticker.systemTicker());
    }

    VectorFeatureService(FeaturifyClient client, Duration ttl, long maximumSize, String defaultGroup, Ticker ticker) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = FeatureCaches.create(ttl, maximumSize, ticker);
        this.defaultGroup = defaultGroup;
    }

    public boolean isEnabled(String key, String element) {
        return isEnabled(key, element, defaultGroup);
    }

    public boolean isEnabled(String key, String element, String group) {
        return getFeature(key, element, group).getValue();
    }

    public BooleanFeatureResponse getFeature(String key, String element) {
        return getFeature(key, element, defaultGroup);
    }

    /**
     * Returns the cached response including its version. Null or empty group selects Global.
     */
    public BooleanFeatureResponse getFeature(String key, String element, String group) {
        GetVectorFeatureRequest cacheKey = GetVectorFeatureRequest.newBuilder().setKey(key).setElement(element)
                .setGroup(group == null ? "" : group).build();
        return cache.get(cacheKey, request -> client.getVectorFeature(request.getKey(), request.getElement(), request.getGroup()));
    }
}
