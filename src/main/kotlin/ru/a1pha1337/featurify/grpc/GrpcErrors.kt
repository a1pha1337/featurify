package ru.a1pha1337.featurify.grpc

import com.google.protobuf.Any
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto

/** Standard gRPC rich errors; clients use code/reason rather than parsing descriptions. */
object GrpcErrors {
    fun exception(status: Status, reason: String, message: String,
                  violations: List<Pair<String, String>> = emptyList()): StatusRuntimeException {
        val details = com.google.rpc.Status.newBuilder().setCode(status.code.value()).setMessage(message)
            .addDetails(Any.pack(ErrorInfo.newBuilder().setDomain("featurify").setReason(reason).build()))
        if (violations.isNotEmpty()) {
            details.addDetails(Any.pack(BadRequest.newBuilder().addAllFieldViolations(violations.map {
                BadRequest.FieldViolation.newBuilder().setField(it.first).setDescription(it.second).build()
            }).build()))
        }
        return StatusProto.toStatusRuntimeException(details.build())
    }
}
