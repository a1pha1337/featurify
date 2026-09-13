package ru.a1pha1337.featurify.client.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class FeatureCaches {
    private FeatureCaches() {
    }

    static <K, V> Cache<K, V> create(Duration ttl, long maximumSize, Ticker ticker) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("featurify.grpc.cache.ttl must not be null or negative");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("featurify.grpc.cache.maximum-size must be positive");
        }
        final long ttlNanos;
        try {
            ttlNanos = ttl.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("featurify.grpc.cache.ttl is too large", exception);
        }
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttlNanos, TimeUnit.NANOSECONDS)
                .ticker(ticker)
                .build();
    }
}
