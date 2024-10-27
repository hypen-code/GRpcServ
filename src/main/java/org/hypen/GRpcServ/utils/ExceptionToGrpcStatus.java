package org.hypen.GRpcServ.utils;

import io.grpc.Status;

import java.util.NoSuchElementException;

public class ExceptionToGrpcStatus {

    /**
     * Translates a Java exception to a gRPC Status object.
     * <p>
     * This method maps common Java exceptions to their corresponding gRPC Status codes,
     * providing a more informative error message to the client.
     * <p>
     * For each mapping, the exception message is included in the Status description.
     *
     * @param exception The Java exception to translate.
     * @return The corresponding gRPC Status object.
     */
    public static Status translateExceptionToStatus(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(exception.getMessage());
        } else if (exception instanceof NoSuchElementException) {
            return Status.NOT_FOUND.withDescription(exception.getMessage());
        } else if (exception instanceof IllegalStateException) {
            return Status.FAILED_PRECONDITION.withDescription(exception.getMessage());
        } else if (exception instanceof NullPointerException) {
            return Status.INVALID_ARGUMENT.withDescription("NullPointerException: " + exception.getMessage());
        } else {
            return Status.INTERNAL.withDescription("Internal server error: " + exception.getMessage());
        }
    }
}