package ru.a1pha1337.featurify.operator;

import java.io.IOException;

public final class ApiException extends IOException {
    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
