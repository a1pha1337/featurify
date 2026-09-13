package ru.a1pha1337.featurify.client;

import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;

/** Blocking, thread-safe client. RPC failures retain their gRPC status and trailers. */
public interface FeaturifyClient {
    BooleanFeatureResponse getBooleanFeature(String key, String group);

    default BooleanFeatureResponse getBooleanFeature(String key) {
        return getBooleanFeature(key, null);
    }

    EnumFeatureResponse getEnumFeature(String key, String group);

    default EnumFeatureResponse getEnumFeature(String key) {
        return getEnumFeature(key, null);
    }

    BooleanFeatureResponse getVectorFeature(String key, String element, String group);

    default BooleanFeatureResponse getVectorFeature(String key, String element) {
        return getVectorFeature(key, element, null);
    }
}
