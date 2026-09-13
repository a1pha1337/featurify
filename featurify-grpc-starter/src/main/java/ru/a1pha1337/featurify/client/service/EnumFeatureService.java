package ru.a1pha1337.featurify.client.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest;

import java.time.Duration;
import java.util.Objects;

/**
 * Blocking, thread-safe access to enum features, with a per-service response cache.
 * TTL is measured from a successful load; reads do not extend it. Zero TTL disables caching.
 * RPC failures propagate unchanged and are never cached.
 * Overloads without a group use the configured default group, or Global when unset.
 */
public class EnumFeatureService {
    private final FeaturifyClient client;
    private final Cache<GetFeatureRequest, EnumFeatureResponse> cache;
    private final String defaultGroup;

    public EnumFeatureService(FeaturifyClient client, Duration ttl, long maximumSize) {
        this(client, ttl, maximumSize, null);
    }

    public EnumFeatureService(FeaturifyClient client, Duration ttl, long maximumSize, String defaultGroup) {
        this(client, ttl, maximumSize, defaultGroup, Ticker.systemTicker());
    }

    EnumFeatureService(FeaturifyClient client, Duration ttl, long maximumSize, String defaultGroup, Ticker ticker) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = FeatureCaches.create(ttl, maximumSize, ticker);
        this.defaultGroup = defaultGroup;
    }

    public String getValue(String key) {
        return getValue(key, defaultGroup);
    }

    public String getValue(String key, String group) {
        return getFeature(key, group).getValue();
    }

    public EnumFeatureResponse getFeature(String key) {
        return getFeature(key, defaultGroup);
    }

    /**
     * Returns the cached response including its version. Null or empty group selects Global.
     */
    public EnumFeatureResponse getFeature(String key, String group) {
        GetFeatureRequest cacheKey = GetFeatureRequest.newBuilder().setKey(key)
                .setGroup(group == null ? "" : group).build();
        return cache.get(cacheKey, request -> client.getEnumFeature(request.getKey(), request.getGroup()));
    }
}
