package ru.a1pha1337.featurify.client

import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse

/** Blocking, thread-safe client. RPC failures propagate as gRPC StatusRuntimeException, including trailers. */
interface FeaturifyClient {
    fun getBooleanFeature(
        key: String,
        group: String? = null,
    ): BooleanFeatureResponse

    fun getEnumFeature(
        key: String,
        group: String? = null,
    ): EnumFeatureResponse

    fun getVectorFeature(
        key: String,
        element: String,
        group: String? = null,
    ): BooleanFeatureResponse
}
